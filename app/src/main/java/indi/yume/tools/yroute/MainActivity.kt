package indi.yume.tools.yroute

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import indi.yume.tools.yroute.test4.*


class MainActivity : BaseActivity() {
    val core: CoreEngine<ActivitiesState> by lazy { (application as App).core }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.text_view).text = "hash: ${this.hashCode()}"
        findViewById<Button>(R.id.new_activity_button).setOnClickListener {
            ActivitiesRoute.run {
                createActivityIntent<BaseActivity, ActivitiesState>(ActivityBuilder(OtherActivity::class.java))
                    .flatMapR { startActivityForResult(it, 1) }
            }
                .start(core)
                .unsafeRunAsync { result ->
                    Logger.d("MainActivity", result.toString())
                }
//            findViewById<TextView>(R.id.text_view).setText("hash: ${this.hashCode()}")
        }

        findViewById<Button>(R.id.fragment_stack_button).setOnClickListener {
            StackActivityRoute
                .startStackFragActivity(ActivityBuilder(FragmentStackActivity::class.java))
                .start(core)
                .unsafeRunAsync { result ->
                    Logger.d("FragmentStackActivity", result.toString())
                }
//            recreate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
    }
}

class TestFragment : Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager
    }
}

