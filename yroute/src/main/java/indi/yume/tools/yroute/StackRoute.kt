package indi.yume.tools.yroute

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import arrow.core.*
import arrow.core.extensions.either.monad.flatMap
import arrow.data.ReaderT
import arrow.effects.IO
import arrow.effects.extensions.io.monadDefer.binding
import arrow.optics.Lens
import arrow.optics.PLens
import arrow.optics.PSetter
import indi.yume.tools.yroute.datatype.*
import indi.yume.tools.yroute.datatype.Success
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import java.lang.ClassCastException
import kotlin.random.Random
import androidx.annotation.AnimRes


//data class FragActivityData<out VD>(override val activity: FragmentActivity,
//                                    override val tag: Any? = null,
//                                    override val state: VD) : ActivityItem, FragActivityItem<VD>

const val EXTRA_KEY__STACK_ACTIVITY_DATA = "extra_key__stack_activity_data"

fun ActivityData.getStackExtra(): StackActivityExtraState<*, *>? =
    extra[EXTRA_KEY__STACK_ACTIVITY_DATA] as? StackActivityExtraState<*, *>

fun ActivityData.putStackExtra(extraState: StackActivityExtraState<*, *>): ActivityData =
    copy(extra = extra + (EXTRA_KEY__STACK_ACTIVITY_DATA to extraState))


data class StackActivityExtraState<F, Type : StackType<F>>(
    val activity: FragmentActivity,
    val state: StackFragState<F, Type>)

data class StackFragState<F, out Type : StackType<F>>(
    val host: StackHost<F, Type>,
    val stack: Type,
    val fm: FragmentManager)

interface StackHost<F, out T : StackType<F>> {
    @get:IdRes
    val fragmentId: Int

    val initStack: T

    var controller: StackController

    var fragmentExitAnim: Int
        get() = controller.fragmentExitAnim
        set(value) {
            controller.fragmentExitAnim = value
        }

    var fragmentEnterAnim: Int
        get() = controller.fragmentEnterAnim
        set(value) {
            controller.fragmentEnterAnim = value
        }

    var activityEnterStayAnim: Int
        get() = controller.activityEnterStayAnim
        set(value) {
            controller.activityEnterStayAnim = value
        }
}

interface StackController {
    var hashTag: Long?

    @get:AnimRes
    var fragmentExitAnim: Int
    @get:AnimRes
    var fragmentEnterAnim: Int
    @get:AnimRes
    var activityEnterStayAnim: Int

    companion object {
        fun defaultController(): StackController = StackControllerImpl()
    }
}

class StackControllerImpl(
        override var hashTag: Long? = null,
        override var fragmentExitAnim: Int = R.anim.fragment_left_exit,
        override var fragmentEnterAnim: Int = R.anim.fragment_left_enter,
        override var activityEnterStayAnim: Int = R.anim.stay_anim) : StackController

typealias TableTag = String

sealed class StackType<T> {
    data class Single<T>(val list: List<FItem<T>> = emptyList()) : StackType<T>()
    data class Table<T>(val defaultMap: Map<TableTag, Class<out T>> = emptyMap(),
                        val defaultTag: TableTag = "default___tag",
                        val table: Map<TableTag, List<FItem<T>>> = emptyMap(),
                        val current: Pair<TableTag, FItem<T>?>? = null) : StackType<T>() {
        companion object {
            fun <T> create(defaultMap: Map<TableTag, Class<out T>> = emptyMap(),
                           defaultTag: TableTag = "default___tag"): Table<T> =
                Table(defaultMap = defaultMap, defaultTag = defaultTag)
        }
    }
}

data class FItem<T>(val t: T, val hashTag: Long, val tag: Any? = null)

open class FragmentBuilder<out F>(val clazz: Class<out F>) {
    internal var createIntent: RouteCxt.() -> Intent = {
        Intent(app, clazz)
    }

    internal var doForFragment: RouteCxt.(Any) -> Unit = { }

    var stackTag: TableTag? = null

    var fragmentTag: Any? = null

    @AnimRes var enterAnim: Int? = null
    @AnimRes var exitAnim: Int? = null

    fun withParam(data: Bundle): FragmentBuilder<F> {
        createIntent = createIntent andThen { it.putExtras(data) }
        return this
    }

    fun withIntent(f: (Intent) -> Unit): FragmentBuilder<F> {
        createIntent = createIntent andThen { f(it); it }
        return this
    }

    @Suppress("UNCHECKED_CAST")
    fun withFragment(f: RouteCxt.(F) -> Unit): FragmentBuilder<F> {
        val action = doForFragment
        doForFragment = { action(it); f(it as F) }
        return this
    }

    fun withFragmentTag(tag: Any?): FragmentBuilder<F> {
        this.fragmentTag = tag
        return this
    }

    fun withStackTag(tag: TableTag?): FragmentBuilder<F> {
        this.stackTag = tag
        return this
    }
}

interface FragmentParam<T> {
    val injector: Subject<T>

    companion object {
        fun <T> defaultInjecter(): Subject<T> =
            BehaviorSubject.create<T>().toSerialized()
    }
}

fun <T, P> FragmentBuilder<T>.withParam(param: P): FragmentBuilder<T> where T : FragmentParam<P> =
    withFragment { f -> f.injector.onNext(param) }

typealias Checker<T> = (T) -> Boolean


interface StackFragment {
    var controller: FragController

    fun onFragmentResult(requestCode: Int, resultCode: Int, data: Bundle?) {  }

    val requestCode: Int
        get() = controller.requestCode
    val resultCode: Int
        get() = controller.resultCode
    val resultData: Bundle?
        get() = controller.resultData

    fun setResult(resultCode: Int, data: Bundle?) {
        controller.resultCode = resultCode
        controller.resultData = data
    }
}

interface FragController {
    var hashTag: Long?

    var requestCode: Int
    var resultCode: Int
    var resultData: Bundle?

    companion object {
        fun defaultController(): FragController = FragControllerImpl()
    }
}

class FragControllerImpl(
    override var hashTag: Long? = null,
    override var requestCode: Int = -1,
    override var resultCode: Int = -1,
    override var resultData: Bundle? = null
) : FragController

typealias InnerError<S> = Either<Fail, S>

enum class FinishResult {
    FinishOver, FinishParent
}

data class SingleTarget<F>(val backF: FItem<F>?, val target: FItem<F>)

data class TableTarget<F>(val tag: TableTag, val backF: FItem<F>?, val target: FItem<F>)

object StackRoute {

    //<editor-fold defaultstate="collapsed" desc="Routes">
    fun <F, T : StackType<F>> stackActivitySelecter(host: StackHost<F, T>)
            : YRoute<ActivitiesState, Lens<ActivitiesState, StackActivityExtraState<F, T>?>> =
        routeF { state, routeCxt ->
            val item = state.list.firstOrNull { it.hashTag == host.controller.hashTag }
            val extra = item?.getStackExtra()

            val result: Tuple2<ActivitiesState, YResult<Lens<ActivitiesState, StackActivityExtraState<F, T>?>>> =
                if (item == null || extra == null) {
                    state toT Fail("Can not find target StackFragState: target=$host, but stack is ${state.list.joinToString()}")
                } else if (item.activity is FragmentActivity) {
                    val fAct = item.activity as FragmentActivity
                    val stackData = StackActivityExtraState(
                        activity = fAct,
                        state = StackFragState(
                            host = host,
                            stack = host.initStack,
                            fm = fAct.supportFragmentManager
                        )
                    )
                    state.copy(list = state.list.map {
                        if (it.hashTag == host.controller.hashTag)
                            item.putStackExtra(extra)
                        else it
                    }) toT Success(stackActivityLens(host))
                } else {
                    state toT Success(stackActivityLens(host))
                }

            IO.just(result)
        }

    fun <F, T : StackType<F>> stackActivityLens(stackHost: StackHost<F, T>): Lens<ActivitiesState, StackActivityExtraState<F, T>?> = Lens(
        get = { state ->
            val item = state.list.firstOrNull { it.activity === stackHost || it.hashTag == stackHost.controller.hashTag }
            if (stackHost.controller.hashTag == null && item != null)
                stackHost.controller.hashTag = item.hashTag

            val extra = item?.getStackExtra()
            if (item != null && extra is StackActivityExtraState<*, *>)
                extra as StackActivityExtraState<F, T>
            else null
        },
        set = { state, extra ->
            if (extra == null)
                state
            else
                state.copy(list = state.list.map { item ->
                    if (item.activity == extra.activity || item.hashTag == extra.state.host.controller.hashTag)
                        item.putStackExtra(extra)
                    else item
                })
        }
    )

    fun <F, T : StackType<F>> stackActivityGetter(stackActivity: StackHost<F, T>): ReaderT<EitherPartialOf<Fail>, ActivitiesState, StackActivityExtraState<F, T>> =
        ReaderT { state ->
            val extra = stackActivityLens(stackActivity).get(state)

            when {
                extra == null -> Fail("Can not find target activity or target activity is not a StackActivity: " +
                        "target is $stackActivity, but stack is ${state.list.joinToString { it.toString() }}").left()
                else -> (extra as? StackActivityExtraState<F, T>)?.right() ?: Fail("Target activity is not a StackActivity: target=$stackActivity").left()
            }
        }

    val activityItemSetter: PSetter<ActivitiesState, InnerError<ActivitiesState>, ActivitiesState, ActivityData> =
        PSetter { state, f ->
            val item = f(state)

            val target = state.list.withIndex().firstOrNull { it.value.hashTag == item.hashTag }
            if (target == null)
                Fail("Can not find target activity: target is $item, but stack is ${state.list.joinToString { it.toString() }}").left()
            else
                state.copy(list = state.list.toMutableList().apply { set(target.index, item) }).right()
        }

    fun <F, T : StackType<F>> stackStateForActivityLens(): Lens<StackActivityExtraState<F, T>, StackFragState<F, T>> = Lens(
        get = { extra -> extra.state },
        set = { extra, newStackState -> extra.copy(state = newStackState) }
    )

    fun <F, T : StackType<F>> stackTypeLens(): Lens<StackFragState<F, T>, T> = PLens(
        get = { stackState -> stackState.stack },
        set = { stackState, stack -> stackState.copy(stack = stack) }
    )

    fun <F> singleStackFGetter(stackFragment: StackFragment?): ReaderT<EitherPartialOf<Fail>, StackType.Single<F>, SingleTarget<F>> =
        ReaderT { singleStack ->
            val target = if (stackFragment == null)
                singleStack.list.withIndex().lastOrNull()
            else
                singleStack.list.withIndex().firstOrNull { it.value.hashTag == stackFragment.controller.hashTag }

            when {
                target == null -> Fail("Can not find target fragment: target=$stackFragment, but stack is ${singleStack.list.joinToString { it.toString() }}").left()
                target.index == 0 -> SingleTarget(null, target.value).right()
                else -> SingleTarget(singleStack.list[target.index - 1], target.value).right()
            }
        }

    fun <F> tableStackFGetter(stackFragment: StackFragment?): ReaderT<EitherPartialOf<Fail>, StackType.Table<F>, TableTarget<F>> =
        ReaderT { tableStack ->
            fun <K, V> findAtMapList(map: Map<K, List<V>>, checker: (V) -> Boolean): Tuple3<K, V?, V>? {
                for ((k, list) in map) {
                    val result = list.withIndex().firstOrNull { checker(it.value) }
                    if (result != null)
                        return Tuple3(k,
                            if (result.index != 0) list[result.index - 1] else null,
                            result.value)
                }
                return null
            }

            val target = when {
                stackFragment != null -> findAtMapList(tableStack.table) { it.hashTag == stackFragment.controller.hashTag }
                        ?.let { TableTarget(it.a, it.b, it.c) }
                tableStack.current != null -> {
                    val tag = tableStack.current.first
                    val targetList = tableStack.table[tag]
                    val target = targetList?.lastOrNull()
                    if (targetList != null && target != null)
                        TableTarget<F>(tag,
                            targetList.getOrNull(targetList.size - 1),
                            target
                        )
                    else null
                }
                else -> null
            }

            target?.right() ?: Fail("Can not find target fragment: target=$stackFragment, but stack is ${tableStack.table}").left()
        }


    fun <F : Fragment, T : StackType<F>> stackFragActivityOrNull(host: StackHost<F, T>): YRoute<ActivitiesState, StackActivityExtraState<F, T>?> =
        routeF { vd, cxt ->
            IO {
                val item = vd.list.firstOrNull { it.hashTag == host.controller.hashTag }
                val extra = item?.getStackExtra()

                vd toT if (extra != null) {
                    val stackActivityData = item as? StackActivityExtraState<F, T>

                    if (stackActivityData != null) {
                        Success(stackActivityData)
                    } else {
                        Success(null)
                    }
                } else Success(null)
            }
        }

    fun <F : Fragment, T : StackType<F>> stackFragActivity(host: StackHost<F, T>): YRoute<ActivitiesState, StackActivityExtraState<F, T>> =
        routeF { vd, cxt ->
            IO {
                val item = vd.list.firstOrNull { it.hashTag == host.controller.hashTag }
                val extra = item?.getStackExtra()

                if (item != null) {
                    if (extra == null && item.activity is FragmentActivity) {
                        val initExtraState = StackActivityExtraState<F, T>(
                            activity = item.activity as FragmentActivity,
                            state = StackFragState(
                                host = host,
                                stack = host.initStack,
                                fm = item.activity.supportFragmentManager
                            )
                        )

                        vd.copy(list = vd.list.map {
                            if (it.hashTag == host.controller.hashTag)
                                it.putStackExtra(initExtraState)
                            else it
                        }) toT Success(initExtraState)
                    } else if (extra != null) {
                        vd toT Success(extra as StackActivityExtraState<F, T>)
                    } else {
                        vd toT Fail("Find item but not target activity is not a FragmentActivity.")
                    }
                } else vd toT Fail(
                    "fragmentActivity | FragmentActivity not find: " +
                            "target=$host, but activity list=${vd.list.joinToString { it.activity.toString() }}"
                )
            }
        }

    fun <F, T : StackType<F>, A> startStackFragActivity(builder: ActivityBuilder<A>): YRoute<ActivitiesState, A>
            where F : Fragment, A : FragmentActivity, A : StackHost<F, T> =
        ActivitiesRoute.createActivityIntent<A, ActivitiesState>(builder)
            .transform { vd, cxt, intent ->
                binding {
                    val top: Context = vd.list.lastOrNull()?.activity ?: cxt.app

                    !IO { top.startActivity(intent) }

                    val (act) = cxt.bindNextActivity()
                        .firstOrError().toIO()

                    if (act !is StackHost<*, *> || act !is ActivityLifecycleOwner) {
                        vd.copy(list = vd.list + ActivityData(act, CoreID.get())) toT
                                YResult.fail("Stack Activity must implements `StackHost` and `ActivityLifecycleOwner` interface.")
                    } else if (cxt.checkComponentClass(intent, act)) {
                        val host = act as StackHost<F, T>
                        val hashTag = CoreID.get()
                        !IO { act.controller.hashTag = hashTag }

                        vd.copy(list = vd.list + ActivityData(
                            activity = act, hashTag = hashTag,
                            extra = emptyMap()
                        ).putStackExtra(StackActivityExtraState(
                            activity = act as A,
                            state = StackFragState(
                                host = host,
                                stack = host.initStack,
                                fm = act.supportFragmentManager
                            )))) toT YResult.success<A>(act)
                    } else {
                        vd.copy(list = vd.list + ActivityData(act, CoreID.get())) toT
                                YResult.fail("startActivity | start activity is Success, but can not get target activity: " +
                                        "target is ${intent.component?.className} but get is $act", null)
                    }
                }
            }

    fun <F> startFragment(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType<F>>, F>
            where F : Fragment, F : StackFragment =
            createFragment<StackFragState<F, StackType<F>>, F>(builder).flatMapR { f ->
                foldStack<F, F>(putFragAtSingle<F>(builder, f), putFragAtTable<F>(builder, f))
                        .mapInner(lens = stackTypeLens<F, StackType<F>>())
                        .stackTran()
            }

    fun <F> startFragmentForSingle(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType.Single<F>>, F>
            where F : Fragment, F : StackFragment =
        createFragment<StackInnerState<StackType.Single<F>>, F>(builder)
            .flatMapR { putFragAtSingle<F>(builder, it) }
            .mapInner(lens = stackTypeLens<F, StackType.Single<F>>())
            .stackTran()

    fun <F> startFragmentForTable(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType.Table<F>>, F>
            where F : Fragment, F : StackFragment =
        createFragment<StackInnerState<StackType.Table<F>>, F>(builder)
            .flatMapR { putFragAtTable<F>(builder, it) }
            .mapInner(lens = stackTypeLens<F, StackType.Table<F>>())
            .stackTran()

    fun <S, F> YRoute<S, F>.dealFragForResult(requestCode: Int): YRoute<S, F>
            where F : StackFragment =
            mapResult {
                it.controller.requestCode = requestCode
                it
            }

    fun <S, F> YRoute<S, F>.mapToRx(): YRoute<S, Single<Tuple2<Int, Bundle?>>>
            where F : StackFragment, F : FragmentLifecycleOwner {
        val requestCode: Int = Random.nextInt()

        return dealFragForResult(requestCode).mapResult { f ->
            f.bindFragmentLife().ofType(FragmentLifeEvent.OnFragmentResult::class.java)
                .filter { it.requestCode == requestCode }
                .firstOrError()
                .map { it.resultCode toT it.data }
        }
    }

    fun <F : Fragment> switchFragmentAtStackActivity(host: StackHost<F, StackType.Table<F>>, tag: TableTag): YRoute<ActivitiesState, F?> =
        switchStackAtTable<F>(tag)
            .mapInner(lens = stackTypeLens<F, StackType.Table<F>>())
            .stackTran<F, StackType.Table<F>, F?>() // YRoute<StackFragState<F, StackType.Table<F>>, F?>
            .stateNullable()
            .mapState(stackActivityLens(host).composeNonNull(stackStateForActivityLens<F, StackType.Table<F>>()))

    internal fun <S, F> createFragment(builder: FragmentBuilder<F>): YRoute<S, F> where F : Fragment =
        routeF { vd, cxt ->
            val intent = builder.createIntent(cxt)

            val clazzNameE = intent.component?.className?.right()
                ?: YResult.fail<F>("Can not get fragment class name, from intent: $intent").left()

            val fragmentE = clazzNameE.flatMap { clazzName ->
                try {
                    val fragClazz = Class.forName(clazzName)
                    val fragmentInstance = fragClazz.newInstance() as F
                    fragmentInstance.arguments = intent.extras
                    builder.doForFragment(cxt, fragmentInstance)
                    fragmentInstance.right()
                } catch (e: ClassNotFoundException) {
                    Fail("Can not find Fragment class: $clazzName", e).left()
                } catch (e: InstantiationException) {
                    Fail("Can not create Fragment instance.", e).left()
                } catch (e: IllegalAccessException) {
                    Fail("Can not create Fragment instance.", e).left()
                } catch (e: ClassCastException) {
                    Fail("Target Fragment type error.", e).left()
                }
            }

            when (fragmentE) {
                is Either.Right -> IO.just(vd toT YResult.success(fragmentE.b))
                is Either.Left -> IO.just(vd toT fragmentE.a)
            }
        }

    internal data class StackInnerState<out S>(
        val state: S,
        val fragmentId: Int,
        val fm: FragmentManager,
        val ft: FragmentTransaction
    ) {
        fun <T> map(f: (S) -> T): StackInnerState<T> = StackInnerState(
            state = f(state), fragmentId = fragmentId, fm = fm, ft = ft
        )
    }

    internal fun <F, T : StackType<F>, R> YRoute<StackInnerState<StackFragState<F, T>>, R>.stackTran(): YRoute<StackFragState<F, T>, R> =
        routeF { vd, cxt ->
            binding {
                val fm = vd.fm
                val ft = !IO { fm.beginTransaction() }
                val innerState = StackInnerState(vd, vd.host.fragmentId, fm, ft)

                val (newInnerState, result) = !this@stackTran.runRoute(innerState, cxt)

                !IO { newInnerState.ft.commitAllowingStateLoss() }

                newInnerState.state toT result
            }
        }

    internal fun <S, Sub, R> YRoute<StackInnerState<Sub>, R>.mapInner(
        type: TypeCheck<S> = type(), lens: Lens<S, Sub>): YRoute<StackInnerState<S>, R> =
        routeF { vd, cxt ->
            binding {
                val (newSVD, result) = !this@mapInner.runRoute(vd.map(lens::get), cxt)

                val newVD = lens.set(vd.state, newSVD.state)

                vd.copy(state = newVD) toT result
            }
        }

    internal fun <F> putFragAtSingle(builder: FragmentBuilder<F>, fragment: F): YRoute<StackInnerState<StackType.Single<F>>, F>
            where F : Fragment, F : StackFragment =
        routeF { vd, cxt ->
            val newItem = FItem<F>(fragment, CoreID.get(), builder.fragmentTag)
            fragment.controller.hashTag = newItem.hashTag

            val stackState = vd.state
            val newStack = stackState.copy(list = stackState.list + newItem)

            IO {
                for (i in stackState.list.size - 1 downTo 0) {
                    val f = stackState.list[i]
                    if (f.t.isVisible) vd.ft.hide(f.t)
                }
                vd.ft.add(vd.fragmentId, fragment)

                vd.copy(state = newStack) toT
                        YResult.success(fragment)
            }
        }

    internal fun <F> putFragAtTable(builder: FragmentBuilder<F>, fragment: F): YRoute<StackInnerState<StackType.Table<F>>, F>
            where F : Fragment, F : StackFragment =
        routeF { vd, cxt ->
            val newItem = FItem<F>(fragment, CoreID.get(), builder.fragmentTag)
            fragment.controller.hashTag = newItem.hashTag

            val oldStackState = vd.state
            val stackTag = builder.stackTag

            val targetTag = stackTag ?: oldStackState.current?.first ?: oldStackState.defaultTag

            binding {
                val innerState =
                    if (oldStackState.current == null || oldStackState.current.first != stackTag) {
                        val (state, beforeFrag) = !switchStackAtTable<F>(targetTag, true).runRoute(vd, cxt)
                        state
                    } else vd

                val innerStackState = innerState.state
                val targetStack = innerStackState.table[targetTag] ?: emptyList()
                val newTable = innerState.state.table - targetTag + (targetTag to targetStack + newItem)

                !IO { vd.ft.add(vd.fragmentId, fragment) }

                innerState.copy(
                    state = innerState.state.copy(
                        table = newTable,
                        current = targetTag to newItem
                    )
                ) toT Success(fragment)
            }
        }

    internal fun <F> switchStackAtTable(targetTag: TableTag, silentSwitch: Boolean = false): YRoute<StackInnerState<StackType.Table<F>>, F?> where F : Fragment =
        routeF { vd, cxt ->
            val stackState = vd.state
            val currentTag = stackState.current?.first

            if (targetTag == currentTag) {
                IO.just(vd toT Success(stackState.table[currentTag]?.lastOrNull()?.t))
            } else binding {
                if (currentTag != null) {
                    val currentStack = stackState.table[currentTag] ?: emptyList()

                    !IO {
                        for (i in currentStack.size - 1 downTo 0) {
                            val f = currentStack[i]
                            if (f.t.isVisible) vd.ft.hide(f.t)
                        }
                    }
                }

                val targetStack = stackState.table[targetTag] ?: emptyList()
                if (targetStack.isEmpty()) {
                    // 如果准备切换到的目标堆栈为空的话，并且配置有默认Fragment的话, 切换到目标Stack并启动默认Fragment
                    val defaultFragClazz: Class<out F>? = stackState.defaultMap[targetTag]

                    if (defaultFragClazz != null) {
                        val (_, result) = !createFragment<Unit, F>(FragmentBuilder(defaultFragClazz).withStackTag(targetTag))
                            .runRoute(Unit, cxt)

                        when (result) {
                            is Success -> !IO {
                                vd.ft.add(vd.fragmentId, result.t)
                                if (silentSwitch) vd.ft.hide(result.t)

                                val newItem = FItem<F>(result.t, CoreID.get(), targetTag)
                                vd.copy(
                                    state = vd.state.copy(
                                        table = stackState.table + (targetTag to targetStack + newItem),
                                        current = targetTag to newItem
                                    )
                                ) toT Success(result.t)
                            }
                            is Fail -> vd toT result
                        }
                    } else vd.copy(state = vd.state.copy(current = targetTag to null)) toT YResult.success(null)
                } else !IO {
                    val item = targetStack.last()
                    if (!silentSwitch) vd.ft.show(item.t)

                    vd.copy(state = vd.state.copy(current = targetTag to item)) toT Success(item.t)
                }
            }
        }

    internal fun <F : Fragment, R> foldStack(
        single: YRoute<StackInnerState<StackType.Single<F>>, R>,
        table: YRoute<StackInnerState<StackType.Table<F>>, R>
    ): YRoute<StackInnerState<StackType<F>>, R> = routeF { state, cxt ->
        val stack = state.state
        @Suppress("UNCHECKED_CAST")
        when(stack) {
            is StackType.Single<F> -> single.runRoute(state as StackInnerState<StackType.Single<F>>, cxt)
            is StackType.Table<F> -> table.runRoute(state as StackInnerState<StackType.Table<F>>, cxt)
        }
    }

    internal fun <F : Fragment, R> foldForFragState(
            single: YRoute<StackFragState<F, StackType.Single<F>>, R>,
            table: YRoute<StackFragState<F, StackType.Table<F>>, R>
    ): YRoute<StackFragState<F, StackType<F>>, R> = routeF { state, cxt ->
        val stack = state.stack
        @Suppress("UNCHECKED_CAST")
        when(stack) {
            is StackType.Single<F> -> single.runRoute(state as StackFragState<F, StackType.Single<F>>, cxt)
            is StackType.Table<F> -> table.runRoute(state as StackFragState<F, StackType.Table<F>>, cxt)
        }
    }

    fun <F, T> dealFinishResultForActivity(activity: Activity, finishResult: FinishResult): YRoute<ActivitiesState, Unit>
            where F : Fragment, T : StackType<F> =
        when (finishResult) {
            FinishResult.FinishOver -> routeId()
            FinishResult.FinishParent -> ActivitiesRoute.findTargetActivityItem(activity)
                .resultNonNull("findTargetActivityItem at dealFinishResultForActivity()")
                .flatMapR { data -> ActivitiesRoute.finishTargetActivity(data) }
        }

    fun <F> dealFinishForResult(finishF: F, backF: F): IO<Unit> where F : StackFragment = IO {
        finishF.controller.apply {
            if (requestCode != -1)
                backF.onFragmentResult(requestCode, resultCode, resultData)
        }
        Unit
    }

    fun <F> finishFragmentForSingle(target: StackFragment?): YRoute<StackFragState<F, StackType.Single<F>>, Tuple2<SingleTarget<F>?, FinishResult>> where F : Fragment =
        finishFragmentAtSingle<F>(target).mapInner(lens = stackTypeLens<F, StackType.Single<F>>()).stackTran()

    fun <F> finishFragmentForTable(target: StackFragment?): YRoute<StackFragState<F, StackType.Table<F>>, Tuple2<TableTarget<F>?, FinishResult>> where F : Fragment =
        finishFragmentAtTable<F>(target).mapInner(lens = stackTypeLens<F, StackType.Table<F>>()).stackTran()

    internal fun <F : Fragment> finishFragmentAtSingle(targetF: StackFragment?): YRoute<StackInnerState<StackType.Single<F>>, Tuple2<SingleTarget<F>?, FinishResult>> =
        routeF { state, cxt ->
            val stack = state.state

            val result = singleStackFGetter<F>(targetF).run(stack).fix()

            val target = when (result) {
                is Either.Left -> return@routeF when {
                    targetF == null -> IO.just(state toT Success(null toT FinishResult.FinishParent))
                    else -> IO.just(state toT result.a)
                }
                is Either.Right -> result.b
            }
            val (backItem, targetItem) = target

            binding {
                val newState =
                    state.copy(state = stack.copy(list = stack.list.filter { it.hashTag != targetItem.hashTag }))

                !IO {
                    state.ft.show(targetItem.t)
                    if (backItem != null) state.ft.hide(backItem.t)
                }

                if (targetItem.t is StackFragment && backItem != null && backItem.t is StackFragment)
                    !dealFinishForResult(targetItem.t, backItem.t)

                newState toT Success(
                        target toT when {
                            newState.state.list.isEmpty() -> FinishResult.FinishParent
                            else -> FinishResult.FinishOver
                        }
                )
            }
        }

    internal fun <F : Fragment> finishFragmentAtTable(target: StackFragment?): YRoute<StackInnerState<StackType.Table<F>>, Tuple2<TableTarget<F>?, FinishResult>> =
        routeF { state, cxt ->
            val stack = state.state

            val result = tableStackFGetter<F>(target).run(stack).fix()

            val targetItem = when (result) {
                is Either.Left -> return@routeF when {
                    target == null -> IO.just(state toT Success(null toT FinishResult.FinishParent))
                    else -> IO.just(state toT result.a)
                }
                is Either.Right -> result.b
            }
            val (targetTag, backF, targetF) = targetItem

            binding {
                val newState = state.copy(state = stack.copy(
                    table = stack.table + (targetTag to (stack.table[targetTag]?.filter { it.hashTag != targetF.hashTag }
                        ?: emptyList())),
                    current = if (stack.current?.second?.hashTag == targetF.hashTag) backF?.let { targetTag to it } else stack.current))

                !IO {
                    state.ft.show(targetF.t)
                    if (backF != null) state.ft.hide(backF.t)
                }

                if (targetF.t is StackFragment && backF != null && backF.t is StackFragment)
                    !dealFinishForResult(targetF.t, backF.t)

                newState toT Success(
                        targetItem toT if (newState.state.table[targetTag].isNullOrEmpty()) FinishResult.FinishParent
                        else FinishResult.FinishOver
                )
            }
        }
    //</editor-fold>

    fun <F, T, A> routeStartStackActivity(builder: ActivityBuilder<A>)
            : YRoute<ActivitiesState, A>
            where F : Fragment, T : StackType<F>, A : FragmentActivity, A : StackHost<F, T> =
            startStackFragActivity(builder)

    fun <F, T, A> routeGetStackFromActivity(activity: A)
            : YRoute<ActivitiesState, Lens<ActivitiesState, StackFragState<F, T>?>>
            where F : Fragment, T : StackType<F>, A : FragmentActivity, A : StackHost<F, T> =
            routeF { actState, routeCxt ->
                val host = activity as StackHost<F, T>
                val lens = stackActivityLens(host).composeNonNull(stackStateForActivityLens<F, T>())
                IO.just(actState toT Success(lens))
            }

    fun <F, T> routeGetStackActivityFromFrag(frag: Fragment)
            : YRoute<ActivitiesState, Lens<ActivitiesState, StackFragState<F, T>?>>
            where F : Fragment, T : StackType<F> =
            routeF { actState, routeCxt ->
                val activity = frag.activity
                if (activity == null) {
                    IO.just(actState toT Fail("Can not find parent stack activity: frag=$frag, parent=$activity"))
                } else if (activity !is StackHost<*, *>) {
                    IO.just(actState toT Fail("Parent activity must be implements `StackHost` interface: frag=$frag, parent=$activity"))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val host = activity as StackHost<F, T>
                    val lens = stackActivityLens(host).composeNonNull(stackStateForActivityLens<F, T>())
                    IO.just(actState toT Success(lens))
                }
            }

    fun <F, R> routeRunAtFrag(frag: Fragment, route: YRoute<StackFragState<F, StackType<F>>, R>): YRoute<ActivitiesState, R>
            where F : Fragment =
            routeGetStackActivityFromFrag<F, StackType<F>>(frag).composeState(route.stateNullable("routeRunAtFrag"))

    infix fun <F, R> YRoute<StackFragState<F, StackType<F>>, R>.runAtF(frag: Fragment): YRoute<ActivitiesState, R>
            where F : Fragment =
            routeRunAtFrag(frag, this)

    fun <F, A, T, R> routeRunAtAct(act: A, route: YRoute<StackFragState<F, T>, R>): YRoute<ActivitiesState, R>
            where F : Fragment, T : StackType<F>, A : FragmentActivity, A : StackHost<F, T> =
            routeGetStackFromActivity(act).composeState(route.stateNullable("routeRunAtFrag"))

    infix fun <F, A, T, R> YRoute<StackFragState<F, T>, R>.runAtA(act: A): YRoute<ActivitiesState, R>
            where F : Fragment, T : StackType<F>, A : FragmentActivity, A : StackHost<F, T> =
            routeRunAtAct(act, this)

    fun <F> routeStartFragmentAtSingle(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType.Single<F>>, F>
            where F : Fragment, F : StackFragment =
            startFragmentForSingle(builder)

    fun <F> routeStartFragmentAtTable(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType.Table<F>>, F>
            where F : Fragment, F : StackFragment =
            startFragmentForTable(builder)

    fun <F> routeStartFragment(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType<F>>, F>
            where F : Fragment, F : StackFragment =
            startFragment(builder)

    fun <F> routeStartFragmentForResultAtSingle(builder: FragmentBuilder<F>, requestCode: Int): YRoute<StackFragState<F, StackType.Single<F>>, F>
            where F : Fragment, F : StackFragment =
            startFragmentForSingle(builder).dealFragForResult(requestCode)

    fun <F> routeStartFragmentForResultAtTable(builder: FragmentBuilder<F>, requestCode: Int): YRoute<StackFragState<F, StackType.Table<F>>, F>
            where F : Fragment, F : StackFragment =
            startFragmentForTable(builder).dealFragForResult(requestCode)

    fun <F> routeStartFragmentForResult(builder: FragmentBuilder<F>, requestCode: Int): YRoute<StackFragState<F, StackType<F>>, F>
            where F : Fragment, F : StackFragment =
            foldForFragState(
                    routeStartFragmentForResultAtSingle(builder, requestCode),
                    routeStartFragmentForResultAtTable(builder, requestCode))

    fun <F> routeStartFragmentForRxAtSingle(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType.Single<F>>, Single<Tuple2<Int, Bundle?>>>
            where F : Fragment, F : StackFragment, F : FragmentLifecycleOwner =
            startFragmentForSingle(builder).mapToRx()

    fun <F> routeStartFragmentForRxAtTable(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType.Table<F>>, Single<Tuple2<Int, Bundle?>>>
            where F : Fragment, F : StackFragment, F : FragmentLifecycleOwner =
            startFragmentForTable(builder).mapToRx()

    fun <F> routeStartFragmentForRx(builder: FragmentBuilder<F>): YRoute<StackFragState<F, StackType<F>>, Single<Tuple2<Int, Bundle?>>>
            where F : Fragment, F : StackFragment, F : FragmentLifecycleOwner =
            foldForFragState(
                    routeStartFragmentForRxAtSingle(builder),
                    routeStartFragmentForRxAtTable(builder))

    fun <F> routeSwitchTag(tag: TableTag): YRoute<StackFragState<F, StackType.Table<F>>, F?> where F : Fragment =
            switchStackAtTable<F>(tag).mapInner(lens = stackTypeLens<F, StackType.Table<F>>()).stackTran()

    fun <F : Fragment> routeFinishFragmentAtSingle(target: StackFragment?): YRoute<StackFragState<F, StackType.Single<F>>, Tuple2<SingleTarget<F>?, FinishResult>> =
            finishFragmentAtSingle<F>(target).mapInner(lens = stackTypeLens<F, StackType.Single<F>>()).stackTran()

    fun <F : Fragment> routeFinishFragmentAtTable(target: StackFragment?): YRoute<StackFragState<F, StackType.Table<F>>, Tuple2<TableTarget<F>?, FinishResult>> =
            finishFragmentAtTable<F>(target).mapInner(lens = stackTypeLens<F, StackType.Table<F>>()).stackTran()

    fun <F : Fragment> routeFinishFragment(target: StackFragment?): YRoute<StackFragState<F, StackType<F>>, Tuple2<Either<SingleTarget<F>, TableTarget<F>>, FinishResult>> =
            foldForFragState(
                    routeFinishFragmentAtSingle<F>(target)
                            .mapResult { (it.a.left() as Either<SingleTarget<F>, TableTarget<F>>) toT it.b },
                    routeFinishFragmentAtTable<F>(target)
                            .mapResult { (it.a.right() as Either<SingleTarget<F>, TableTarget<F>>) toT it.b })

    fun <F, A> routeStartFragAtNewSingleActivity(activityBuilder: ActivityBuilder<A>,
                                                 fragBuilder: FragmentBuilder<F>)
            : YRoute<ActivitiesState, Tuple2<A, F>>
            where F : Fragment, F : StackFragment, A : FragmentActivity, A : StackHost<F, StackType.Single<F>> =
            routeStartStackActivity(activityBuilder)
                    .flatMapR { activity ->
                        startFragmentForSingle(fragBuilder)
                                .mapStateNullable(lens = stackActivityLens<F, StackType.Single<F>>(activity)
                                        .composeNonNull(stackStateForActivityLens<F, StackType.Single<F>>()))
                                .mapResult { f -> activity toT f }
                    }

    fun <F, A> routeStartFragAtNewTableActivity(activityBuilder: ActivityBuilder<A>,
                                                fragBuilder: FragmentBuilder<F>)
            : YRoute<ActivitiesState, Tuple2<A, F>>
            where F : Fragment, F : StackFragment, A : FragmentActivity, A : StackHost<F, StackType.Table<F>> =
            routeStartStackActivity(activityBuilder)
                    .flatMapR { activity ->
                        startFragmentForTable(fragBuilder)
                                .mapStateNullable(lens = stackActivityLens<F, StackType.Table<F>>(activity)
                                        .composeNonNull(stackStateForActivityLens<F, StackType.Table<F>>()))
                                .mapResult { f -> activity toT f }
                    }
}