package com.dds.webrtc_player

import android.os.Bundle
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.withCreated
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.dds.webrtc_player.databinding.ActivityMain2Binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.SurfaceViewRenderer
import kotlin.coroutines.CoroutineContext

class MainActivity2 : AppCompatActivity() {

    private lateinit var webRTCPlayer: WebRTCPlayer
//    private lateinit var webRTCPlayer: WebRTCPlayerWithCoroutine

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMain2Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMain2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }

        val surfaceView = findViewById<SurfaceViewRenderer>(R.id.surface_view_renderer)
        webRTCPlayer = WebRTCPlayer(
            context = this,
            surfaceView = surfaceView,
            wsUrl = "wss://unrealstreaming.net:444/webrtc_playnow/singleport/tcp/2a851cef-fbc0-45fa-b008-385d1da96383"
        )

        webRTCPlayer.start()

//        webRTCPlayer = WebRTCPlayerWithCoroutine(
//            context = this,
//            surfaceView = surfaceView,
//            wsUrl = "wss://unrealstreaming.net:444/webrtc_playnow/singleport/tcp/0f7cb3d8-a9dd-4b85-b427-0c8d561d580b",
//            scope = CoroutineScope((Dispatchers.IO))
//        )
//
//       CoroutineScope(Dispatchers.IO).launch {
//           try {
//               webRTCPlayer.start()
//           } catch (e: Exception) {
//               Log.e("WebRTC", "Failed to start: ${e.message}")
//           }
//       }



    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
    override fun onDestroy() {
        super.onDestroy()
        webRTCPlayer.stop()
    }

}