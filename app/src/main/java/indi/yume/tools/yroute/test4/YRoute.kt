package indi.yume.tools.yroute.test4

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.CheckResult
import arrow.core.*
import arrow.data.*
import arrow.data.extensions.kleisli.monad.monad
import arrow.effects.ForIO
import arrow.effects.IO
import arrow.effects.MVar
import arrow.effects.extensions.io.applicativeError.handleError
import arrow.effects.extensions.io.async.async
import arrow.effects.extensions.io.monad.monad
import arrow.effects.extensions.io.monadDefer.binding
import arrow.effects.fix
import arrow.optics.Lens
import indi.yume.tools.yroute.Logger
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlin.random.Random


//<editor-fold desc="Result<T>">
sealed class Result<out T> {
    companion object {
        fun <T> success(t: T): Result<T> = Success(t)

        fun <T> fail(message: String, error: Throwable? = null): Result<T> = Fail(message, error)
    }
}
data class Success<T>(val t: T) : Result<T>()
data class Fail(val message: String, val error: Throwable? = null) : Result<Nothing>()

fun <T, R> Result<T>.map(mapper: (T) -> R): Result<R> = flatMap { Success(mapper(it)) }

inline fun <T, R> Result<T>.flatMap(f: (T) -> Result<R>): Result<R> = when(this) {
    is Success -> f(t)
    is Fail -> this
}

fun <T> Result<T>.toEither(): Either<Fail, T> = when (this) {
    is Fail -> left<Fail>()
    is Success -> t.right()
}
//</editor-fold>


//<editor-fold desc="YRoute">
typealias YRoute<S, R> = StateT<ReaderTPartialOf<ForIO, RouteCxt>, S, Result<R>>


inline fun <S, R> route(crossinline f: (S) -> (RouteCxt) -> IO<Tuple2<S, Result<R>>>): YRoute<S, R> =
    StateT(ReaderT.monad<ForIO, RouteCxt>(IO.monad()))
    { state -> ReaderT { f(state)(it) } }

inline fun <S, R> routeF(crossinline f: (S, RouteCxt) -> IO<Tuple2<S, Result<R>>>): YRoute<S, R> =
    StateT(ReaderT.monad<ForIO, RouteCxt>(IO.monad()))
    { state -> ReaderT { cxt -> f(state, cxt) } }

fun <S, R> YRoute<S, R>.runRoute(state: S, cxt: RouteCxt): IO<Tuple2<S, Result<R>>> =
    this.run(ReaderT.monad(IO.monad()), state).fix().run(cxt).fix()

fun <S> routeId(): YRoute<S, Unit> = routeF { s, c -> IO.just(s toT Success(Unit)) }

fun <S> routeGetState(): YRoute<S, S> = routeF { s, _ -> IO.just(s toT Success(s)) }

fun <S, T> routeFromIO(io: IO<T>): YRoute<S, T> =
    routeF { state, _ -> io.map { state toT Success(it) } }

fun <S, R, R2> YRoute<S, R>.flatMapR(f: (R) -> YRoute<S, R2>): YRoute<S, R2> =
    routeF { state, cxt ->
        binding {
            val (newState1, result1) = !this@flatMapR.runRoute(state, cxt)

            when (result1) {
                is Fail -> newState1 toT result1
                is Success -> !f(result1.t).runRoute(newState1, cxt)
            }
        }
    }

fun <S1, S2, R> YRoute<S1, Lens<S1, S2>>.composeState(route: YRoute<S2, R>): YRoute<S1, R> =
    routeF { state1, cxt ->
        binding {
            val (innerState1, lensResult) = !this@composeState.runRoute(state1, cxt)

            when (lensResult) {
                is Fail -> innerState1 toT lensResult
                is Success -> {
                    val state2 = lensResult.t.get(state1)
                    val (newState2, result) = !route.runRoute(state2, cxt)
                    val newState1 = lensResult.t.set(innerState1, newState2)
                    newState1 toT result
                }
            }
        }
    }

fun <S : Any, R> YRoute<S, R>.stateNullable(): YRoute<S?, R> =
    routeF { state, cxt ->
        if (state == null)
            IO.just(state toT Fail("State is null, can not get state."))
        else
            this@stateNullable.runRoute(state, cxt)
    }

//fun <S1, T1, S2, R> YRoute<S2, R>.changeState(f: (R) -> Lens<S1, S2>): YRoute<S1, R> =
//    mapParam(type<Tuple2<T, T1>>()) { it.b }.transStateByParam { f(it.a) }
//
//fun <S1, S2, R> YRoute<S2, R>.transStateByParam(f: (R) -> Lens<S1, S2>): YRoute<S1, R> =
//    routeF { state1, cxt ->
//        binding {
//            val lens = f()
//            val state2 = lens.get(state1)
//            val (newState1, result) = !this@transStateByParam.runRoute(state2, cxt)
//
//            val newState2 = lens.set(state1, newState1)
//            newState2 toT result
//        }
//    }

//fun <S1, S2, R> YRoute<S2, Lens<S1, S2>>.apForState(): YRoute<S1, Lens<S1, S2>> =
//    routeF { state1, cxt ->
//        binding {
//            val (newState1, result) = !this@apForState.runRoute(state2, cxt, lens)
//
//
//            val state2 = lens.get(state1)
//            val (newState1, result) = !this@apForState.runRoute(state2, cxt, lens)
//            val newState2 = lens.set(state1, newState1)
//            newState2 toT result
//        }
//    }

fun <S, T1, R> YRoute<S, T1>.transform(f: (S, RouteCxt, T1) -> IO<Tuple2<S, Result<R>>>): YRoute<S, R> =
    routeF { state, cxt ->
        binding {
            val (tuple2) = this@transform.runRoute(state, cxt)
            val (newState, resultT1) = tuple2

            when (resultT1) {
                is Success -> !f(newState, cxt, resultT1.t)
                is Fail -> newState toT resultT1
            }
        }
    }

fun <S1, S2, R> YRoute<S1, R>.mapStateF(lensF: () -> Lens<S2, S1>): YRoute<S2, R> =
    routeF { state2, cxt ->
        val lens = lensF()
        val state1 = lens.get(state2)
        binding {
            val (newState1, result) = !this@mapStateF.runRoute(state1, cxt)
            val newState2 = lens.set(state2, newState1)
            newState2 toT result
        }
    }

fun <S1, S2, R> YRoute<S1, R>.mapState(lens: Lens<S2, S1>): YRoute<S2, R> =
    routeF { state2, cxt ->
        val state1 = lens.get(state2)
        binding {
            val (newState1, result) = !this@mapState.runRoute(state1, cxt)
            val newState2 = lens.set(state2, newState1)
            newState2 toT result
        }
    }

fun <S1 : Any, S2, R> YRoute<S1, R>.mapStateNullable(lens: Lens<S2, S1?>): YRoute<S2, R> =
    routeF { state2, cxt ->
        val state1 = lens.get(state2)
            ?: return@routeF IO.just(state2 toT Fail("mapStateNullable | Can not get target State, get target State result is null from lens."))
        binding {
            val (newState1, result) = !this@mapStateNullable.runRoute(state1, cxt)
            val newState2 = lens.set(state2, newState1)
            newState2 toT result
        }
    }

fun <S, T, K : T> YRoute<S, T>.ofType(type: TypeCheck<K>): YRoute<S, K> =
        mapResult { it as K }

fun <S, T1, R> YRoute<S, T1>.mapResult(f: (T1) -> R): YRoute<S, R> =
    transform { state, cxt, t1 -> IO.just(state toT Success(f(t1))) }

fun <S, R : Any> YRoute<S, R?>.resultNonNull(tag: String = "resultNonNull()"): YRoute<S, R> =
    transform { state, cxt, r ->
        IO.just(state toT if (r != null) Success(r) else Fail("Tag $tag | Result can not be Null."))
    }

fun <S, T1, R> YRoute<S, T1>.composeWith(f: (S, RouteCxt, T1) -> IO<Tuple2<S, Result<R>>>): YRoute<S, R> =
    routeF { state, cxt ->
        binding {
            val (tuple2) = this@composeWith.runRoute(state, cxt)
            val (newState, resultT1) = tuple2

            when (resultT1) {
                is Success -> !f(newState, cxt, resultT1.t)
                is Fail -> newState toT resultT1
            }
        }
    }

fun <S1, S2, R1, R2> zipRoute(route1: YRoute<S1, R1>, route2: YRoute<S2, R2>): YRoute<Tuple2<S1, S2>, Tuple2<R1, R2>> =
    routeF { (state1, state2), cxt ->
        binding {
            val (newState1, result1) = !route1.runRoute(state1, cxt)
            val (newState2, result2) = !route2.runRoute(state2, cxt)
            (newState1 toT newState2) toT result1.flatMap { r1 -> result2.map { r2 -> r1 toT r2 } }
        }
    }

fun <S, R1, R2> YRoute<S, Tuple2<R1, R2>>.ignoreLeft(): YRoute<S, R2> = mapResult { it.b }

fun <S, R1, R2> YRoute<S, Tuple2<R1, R2>>.ignoreRight(): YRoute<S, R1> = mapResult { it.a }

fun <S, R1, R2> YRoute<S, Tuple2<R1, R2>>.switchResult(): YRoute<S, Tuple2<R2, R1>> = mapResult { it.b toT it.a }

infix fun <S, R1, R2> YRoute<S, R1>.zipWith(route2: YRoute<S, R2>): YRoute<S, Tuple2<R1, R2>> =
    routeF { state, cxt ->
        binding {
            val (newState, resultT1) = !this@zipWith.runRoute(state, cxt)

            when (resultT1) {
                is Success -> !route2.runRoute(newState, cxt)
                    .map { it.map { r -> r.map { t2 -> resultT1.t toT t2 } } }
                is Fail -> newState toT resultT1
            }
        }
    }

fun <S, SS, R> YRoute<S, YRoute<SS, R>>.flatten(lens: Lens<S, SS>): YRoute<S, R> =
    routeF { state, cxt ->
        binding {
            val (innerS, innerYRouteResult) = !this@flatten.runRoute(state, cxt)

            val sstate = lens.get(innerS)

            when (innerYRouteResult) {
                is Success -> {
                    val (newSS, innerResult) = !innerYRouteResult.t.runRoute(sstate, cxt)

                    val newS = lens.set(innerS, newSS)
                    newS toT innerResult
                }
                is Fail -> innerS toT innerYRouteResult
            }
        }
    }
//</editor-fold>

//<editor-fold desc="CoreEngine">

interface CoreEngine<S> {
    val routeCxt: RouteCxt

    @CheckResult
    fun runIO(io: IO<*>): IO<Unit>

    @CheckResult
    fun putStream(stream: Completable): IO<Unit>

    @CheckResult
    fun <R> runAsync(route: YRoute<S, R>, callback: (Result<R>) -> Unit): IO<Unit>

    @CheckResult
    fun <R> run(route: YRoute<S, R>): IO<Result<R>>
}

typealias CoreContainer = Map<String, *>

typealias ContainerEngine = MainCoreEngine<CoreContainer>

fun <S> ContainerEngine.createBranch(initState: S): SubCoreEngine<S> {
    val key = CoreID.get().toString()
    @Suppress("UNCHECKED_CAST")
    val lens: Lens<CoreContainer, S> = Lens(
        get = { container -> (container[key] as? S) ?: initState },
        set = { container, subState -> container + (key to subState) }
    )
    return subCore(lens)
}

class MainCoreEngine<S>(val state: MVar<ForIO, S>,
                        override val routeCxt: RouteCxt
): CoreEngine<S> {
    private val streamSubject: Subject<Completable> = PublishSubject.create()

    @CheckResult
    override fun runIO(io: IO<*>): IO<Unit> = IO { streamSubject.onNext(io.toSingle().ignoreElement()) }

    @CheckResult
    override fun putStream(stream: Completable): IO<Unit> = IO { streamSubject.onNext(stream) }

    @CheckResult
    override fun <R> runAsync(route: YRoute<S, R>, callback: (Result<R>) -> Unit): IO<Unit> =
        putStream(runActual(route).toCompletable { either ->
            val result = either.fold({ Fail("Has error.", it) }, { it })
            callback(result)
        })

    @CheckResult
    override fun <R> run(route: YRoute<S, R>): IO<Result<R>> =
        IO.async { connection, cb ->
            val io = runAsync(route) { cb(it.right()) }
            val disposable = io.unsafeRunAsyncCancellable(cb = { if (it is Either.Left) cb(it) })
            connection.push(IO { disposable() })
        }

    private fun <R> runActual(route: YRoute<S, R>): IO<Result<R>> = binding {
        val code = Random.nextInt()
        Logger.d("CoreEngine", "================>>>>>>>>>>>> $code")
        Logger.d("CoreEngine", "start run: route=$route")
        val oldState = !state.take()
        Logger.d("CoreEngine", "get oldState: $oldState")
        val (newState, result) = !route.runRoute(oldState, routeCxt)
            .handleError {
                // TODO force restore all state.
                oldState toT Fail("A very serious exception has occurred when run route, Status may become out of sync.", it)
            }
        Logger.d("CoreEngine", "return result: result=$result, newState=$newState")
        !state.put(newState)
        if (newState == oldState)
            Logger.d("CoreEngine", "put newState: state not changed")
        else
            Logger.d("CoreEngine", "put newState: newState=$newState")
        Logger.d("CoreEngine", "================<<<<<<<<<<<< $code")
        result
    }

    fun <S2> subCore(lens: Lens<S, S2>): SubCoreEngine<S2> {
        val subDelegate = object : CoreEngine<S2> {
            override val routeCxt: RouteCxt = this@MainCoreEngine.routeCxt

            override fun runIO(io: IO<*>): IO<Unit> = this@MainCoreEngine.runIO(io)

            override fun putStream(stream: Completable): IO<Unit> =
                this@MainCoreEngine.putStream(stream)

            override fun <R> runAsync(route: YRoute<S2, R>, callback: (Result<R>) -> Unit): IO<Unit> =
                this@MainCoreEngine.runAsync(route.mapState(lens), callback)

            override fun <R> run(route: YRoute<S2, R>): IO<Result<R>> =
                this@MainCoreEngine.run(route.mapState(lens))
        }

        return SubCoreEngine(subDelegate)
    }

    @CheckResult
    fun start(): Completable = streamSubject
        .concatMapCompletable { completable ->
            completable.doOnError { it.printStackTrace() }
                .onErrorComplete()
                .doFinally {
                    Logger.d("concatMapCompletable", "event over")
                }
        }

    companion object {
        fun <S> create(app: Application, initState: S): IO<MainCoreEngine<S>> = binding {
            val mvar = !MVar.uncancelableOf(initState, IO.async())
            val cxt = !RouteCxt.create(app)
            MainCoreEngine(mvar, cxt)
        }
    }
}

class SubCoreEngine<S>(delegate: CoreEngine<S>): CoreEngine<S> by delegate {
    fun <S2> subCore(lens: Lens<S, S2>): SubCoreEngine<S2> {
        val subDelegate = object : CoreEngine<S2> {
            override val routeCxt: RouteCxt = this@SubCoreEngine.routeCxt

            override fun runIO(io: IO<*>): IO<Unit> = this@SubCoreEngine.runIO(io)

            override fun putStream(stream: Completable): IO<Unit> =
                this@SubCoreEngine.putStream(stream)

            override fun <R> runAsync(route: YRoute<S2, R>, callback: (Result<R>) -> Unit): IO<Unit> =
                this@SubCoreEngine.runAsync(route.mapState(lens), callback)

            override fun <R> run(route: YRoute<S2, R>): IO<Result<R>> =
                this@SubCoreEngine.run(route.mapState(lens))
        }

        return SubCoreEngine(subDelegate)
    }
}


fun <S, R> YRoute<S, R>.startAsync(core: CoreEngine<S>, callback: (Result<R>) -> Unit): IO<Unit> =
    core.runAsync(this, callback)

fun <S, R> YRoute<S, R>.start(core: CoreEngine<S>): IO<Result<R>> = core.run(this)

//</editor-fold>

//<editor-fold desc="RouteCxt">

class RouteCxt private constructor(val app: Application) {

    private val streamSubject: Subject<Completable> = PublishSubject.create()

    val globalActivityLife = object : ActivityLifecycleOwner {
        override val lifeSubject: Subject<ActivityLifeEvent> = ActivityLifecycleOwner.defaultLifeSubject()
    }

    val callback: Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: Activity?) {
            if (activity != null)
                globalActivityLife.makeState(ActivityLifeEvent.OnPause(activity))
        }

        override fun onActivityResumed(activity: Activity?) {
            if (activity != null)
                globalActivityLife.makeState(ActivityLifeEvent.OnResume(activity))
        }

        override fun onActivityStarted(activity: Activity?) {
            if (activity != null)
                globalActivityLife.makeState(ActivityLifeEvent.OnStart(activity))
        }

        override fun onActivityDestroyed(activity: Activity?) {
            if (activity != null)
                globalActivityLife.makeState(ActivityLifeEvent.OnDestroy(activity))
        }

        override fun onActivitySaveInstanceState(activity: Activity?, outState: Bundle?) {
            if (activity != null)
                globalActivityLife.makeState(ActivityLifeEvent.OnSaveInstanceState(activity, outState))
        }

        override fun onActivityStopped(activity: Activity?) {
            if (activity != null)
                globalActivityLife.makeState(ActivityLifeEvent.OnStop(activity))
        }

        override fun onActivityCreated(activity: Activity?, savedInstanceState: Bundle?) {
            if (activity != null)
                globalActivityLife.makeState(ActivityLifeEvent.OnCreate(activity, savedInstanceState))
        }

    }

    init {
        app.registerActivityLifecycleCallbacks(callback)
    }

    internal fun start(): Completable =
        streamSubject.flatMapCompletable {
            it.doOnError { it.printStackTrace() }
                .onErrorComplete()
        }

    fun bindNextActivity(): Observable<Activity> = globalActivityLife.bindActivityLife()
        .ofType(ActivityLifeEvent.OnCreate::class.java)
        .map { it.activity }

    fun putStream(stream: Completable): IO<Unit> = IO { streamSubject.onNext(stream) }

    companion object {
        fun create(app: Application): IO<RouteCxt> = IO {
            val cxt = RouteCxt(app)
            cxt.start().subscribe()
            cxt
        }
    }
}
//</editor-fold>