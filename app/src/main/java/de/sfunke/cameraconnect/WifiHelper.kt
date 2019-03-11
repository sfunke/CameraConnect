package de.sfunke.cameraconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import io.reactivex.Completable
import io.reactivex.schedulers.Schedulers
import org.jetbrains.anko.connectivityManager
import org.jetbrains.anko.wifiManager
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Created by Steffen Funke on 05.09.17.
 */
object WifiHelper {

    val logger = LoggerFactory.getLogger("WifiHelper")

    //----------------------------------
    //  Connect
    //----------------------------------
    fun connect(
        context: Context,
        wifiConfiguration: WifiConfiguration,
        accessPoint: Pair<String, Int>
    ): Completable {
        return enableAndConnectToWifi(context, wifiConfiguration)
            .andThen(bindProcessToWifiNetwork(context))
            .andThen(checkForSocketAvailability(context, accessPoint).subscribeOn(Schedulers.io()))
    }

    private fun enableAndConnectToWifi(
        context: Context,
        wifiConfiguration: WifiConfiguration): Completable {

        return Completable.create { emitter ->
            // receiver for connection state change events
            val receiver: BroadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (WifiManager.NETWORK_STATE_CHANGED_ACTION == intent.action) {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                        logger.info(networkInfo.toString())
                        if (networkInfo != null && networkInfo.state == NetworkInfo.State.CONNECTED && networkInfo.detailedState == NetworkInfo.DetailedState.CONNECTED) {
                            // e.g. To check the Network Name or other info:
                            //                            networkInfo.extraInfo
                            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                            val wifiInfo = wifiManager.connectionInfo

                            if (wifiInfo.ssid == wifiConfiguration.SSID) {
                                emitter.onComplete()
                            }
                        }
                    }
                }
            }

            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            logger.info("Before Wifi Add:")
            wm.configuredNetworks?.forEach {
                logger.info("configuredNetwork: ${it.SSID}")
            }

            var existingConf = wm.configuredNetworks.firstOrNull { it.SSID == wifiConfiguration.SSID }
            // if it does not yet exist, add it
            if (existingConf == null) {
                logger.info("======= NETWORK DOES NOT EXIST FOR APP, ADD IT")
                val netId = wm.addNetwork(wifiConfiguration)
                if (netId == -1) logger.error("wm.addNetwork failed: ${wifiConfiguration}")
                logger.info("Net id: ${netId}")
                logger.info("After Wifi Add:")
                wm.configuredNetworks.forEach {
                    logger.info(it.SSID)
                    it.networkId
                }
            }

            // try to get it again
            existingConf = wm.configuredNetworks?.firstOrNull { it.SSID == wifiConfiguration.SSID }
            if (existingConf != null) {
                logger.info("Found: ${existingConf.SSID}")

                // if we are already connected
                val connectionInfo = wm.connectionInfo
                if (connectionInfo != null && connectionInfo.supplicantState == SupplicantState.COMPLETED && connectionInfo.bssid != null && connectionInfo.ssid == existingConf.SSID) {
                    logger.info("Already connected: ${connectionInfo.ssid}")

                    // already connected
                    emitter.onComplete()

                } else {
                    logger.info("======= NOW DISCONNECT WIFI BEFORE CONNECTING AGAIN")
                    val result = wm.disconnect()
                    logger.debug("WIFIMANAGER DISCONNECT, success: ${result}")
                    if (!result) {
                        logger.error("WIFIMANAGER DISCONNECT NO SUCCESS ")
                    }

                    logger.info("======= DISABLE NETWORKS NOT USED FOR CAMERA ACCESS")
                    wm.configuredNetworks.filterNot { it.SSID == existingConf.SSID }.forEach {
                        val success = wm.disableNetwork(it.networkId)
                        logger.debug("disableNetwork ${it.SSID}, success: ${success}")
                        if (!success) {
                            logger.error("Network DISABLE NO SUCCESS for Network: ${it.SSID}")
                        }
                    }
                    logger.info("======= NOW ENABLE CAMERA NETWORK")
                    val success = wm.enableNetwork(existingConf.networkId, true)
                    logger.debug("enableNetwork ${existingConf.SSID}, success: ${success}")
                    if (!success) {
                        logger.error("Network ENABLE NO SUCCESS for Network: ${existingConf.SSID}")
                    }
                    wm.reconnect()
                    // register receiver for network changes (important: this has to come AFTER disconnect / enableNetwork - otherwise it would trigger for changes of the "old" wifi
                    context.registerReceiver(
                        receiver,
                        IntentFilter().apply { addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION) })
                }

            } else {
                emitter.onError(NullPointerException("Wifi Configuration with SSID ${wifiConfiguration.SSID} should exist at this point (we added it)!"))
            }

            emitter.setCancellable {
                logger.info("setCancellable enableAndConnectToWifi, cleaning up ...")
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    logger.info("IllegalArgumentException: Try to unregister not registered Receiver, ignore")
                }
            }
        }
    }

    private fun bindProcessToWifiNetwork(context: Context): Completable {
        return Completable.create { emitter ->
            val builder = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            val callback = object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network?) {
                    context.connectivityManager.bindProcessToNetwork(network)
                    context.connectivityManager.unregisterNetworkCallback(this)
                    emitter.onComplete()
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                }
            }
            context.connectivityManager.registerNetworkCallback(builder.build(), callback)

            emitter.setCancellable {
                logger.info("setCancellable bindProcessToWifiNetwork, cleaning up ...")
                context.connectivityManager.unregisterNetworkCallback(callback)
            }
        }
    }

    private fun checkForSocketAvailability(context: Context, accessPoint: Pair<String, Int>):Completable {
        // Test Loop for Socket Connection (a bit hacky)
        return Completable.create { emitter ->
            val wifiInfo = context.wifiManager.connectionInfo

            var running = true
            emitter.setCancellable {
                logger.info("setCancellable checkForSocketAvailability, cleaning up ...")
                running = false
            }
            while (!emitter.isDisposed && running) {
                try {
                    Socket().use {
                        logger.info("Trying Socket connection ${wifiInfo.ssid} .... ")
                        // Set a timeout of 1 seconds to connect
                        it.connect(
                            InetSocketAddress(accessPoint.first, accessPoint.second),
                            2000
                        )
                        if (it.isConnected) {
                            logger.info("Socket connection success")
                            running = false
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Exception", e)
                }
            }
            emitter.onComplete()
        }
    }



    //----------------------------------
    //  Disconnect
    //----------------------------------
    fun disconnect(context: Context, ssid: String) = Completable.fromRunnable {

        context.connectivityManager.bindProcessToNetwork(null)
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val wifiInfo: WifiInfo? = wm.connectionInfo
        var existingConf: WifiConfiguration? = wm.configuredNetworks.firstOrNull { it.SSID == ssid }

        if (wifiInfo != null && wifiInfo.ssid == ssid && wifiInfo.supplicantState == SupplicantState.COMPLETED) {
            // we are currently connected with given wifi, so disconnect
            logger.info("======= NOW DISCONNECT WIFI")
            val result = wm.disconnect()
            logger.debug("WIFIMANAGER DISCONNECT, success: ${result}")
            if (!result) {
                logger.error("WIFIMANAGER DISCONNECT NO SUCCESS ")
            }

            if (existingConf != null) {
                logger.info("======= NOW DISABLE CAMERA NETWORK")
                val success = wm.disableNetwork(existingConf.networkId)
                logger.debug("disableNetwork ${existingConf.SSID}, success: ${success}")
                if (!success) {
                    logger.error("Network DISABLE NO SUCCESS for Network: ${existingConf.SSID}")
                }
            }
        } else {
            throw RuntimeException("Not connected with Wifi ${ssid} or not correct state")
        }
    }

    //----------------------------------
    //  Helper
    //----------------------------------
    fun getWifiConf(ssid: String, key: String? = null): WifiConfiguration = WifiConfiguration().apply {
        SSID = "\"$ssid\""
        priority = 100000
        if (key.isNullOrBlank()) {
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) // TODO either set this, depending if key is empty
        } else {
            preSharedKey = "\"$key\""
        }
    }

}