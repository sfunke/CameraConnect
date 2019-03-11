package de.sfunke.cameraconnect

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.github.florent37.runtimepermission.kotlin.askPermission
import com.noveogroup.android.log.LoggerManager
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.toast
import org.jetbrains.anko.wifiManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        val logger = LoggerManager.getLogger(MainActivity::class.java)

        const val CAMERA_IP_ADDRESS = "192.168.1.1"
        const val CAMERA_PORT = 15740
        const val SSID = "Nikon_WU2_0090B5210588"
        const val KEY = ""
    }

    var disposable: Disposable? = null
    val wifiConf = WifiHelper.getWifiConf(SSID, KEY)


    enum class State { UNINIT, DISCONNECTED, CONNECTING, CONNECTED }

    var state: State = State.UNINIT
        set(value) {
            field = value
            updateForState()
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        askPermission {}

        state = State.DISCONNECTED
        button2.setOnClickListener {
            val configuredNetworks = wifiManager.configuredNetworks
            configuredNetworks.forEach { logger.w(it.toString()) }

        }
    }


    private fun updateForState() {
        when (state) {
            State.UNINIT -> {

            }
            State.DISCONNECTED -> {
                toast("Verbindung getrennt")
                button.text = "Verbinden mit Kamera"
                textView.text = "Verbindung getrennt"
                button.setOnClickListener {
                    state = State.CONNECTING

                    disposable = WifiHelper.connect(
                        applicationContext,
                        wifiConf,
                        CAMERA_IP_ADDRESS to CAMERA_PORT
                    )
                        .subscribeOn(Schedulers.io())
                        .timeout(20, TimeUnit.SECONDS)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            state = State.CONNECTED
                        }, {
                            textView.text = "Failure: ${it.message}"
                            logger.e(it)
                            toast(it.message.toString())
                            state = State.DISCONNECTED
                        })
                }

            }

            State.CONNECTING -> {
                button.text = "Connecting ... "
                textView.text = "Connecting ..."
                button.setOnClickListener {
                    disposable?.dispose()
                    toast("Verbindung getrennt")
                    state = State.DISCONNECTED
                }
            }

            State.CONNECTED -> {
                toast("Verbunden mit Kamera")
                textView.text = "Verbunden mit Kamera"
                button.text = "Verbindung trennen"
                button.setOnClickListener {
                    WifiHelper.disconnect(this, wifiConf.SSID)
                        .subscribe({
                            textView.text = "Success"
                            state = State.DISCONNECTED
                        }, {
                            textView.text = "Failure: ${it.message}"
                            logger.e(it)
                            toast(it.message.toString())
                        })
                }
            }
        }
    }
}
