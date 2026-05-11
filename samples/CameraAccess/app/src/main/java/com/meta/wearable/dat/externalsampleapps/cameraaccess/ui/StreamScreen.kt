/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val liveKitFieldColors =
      OutlinedTextFieldDefaults.colors(
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          disabledTextColor = Color.White.copy(alpha = 0.78f),
          focusedContainerColor = Color(0xFF101820),
          unfocusedContainerColor = Color(0xFF101820),
          disabledContainerColor = Color(0xFF101820),
          cursorColor = Color.White,
          focusedBorderColor = Color(0xFF7DD3FC),
          unfocusedBorderColor = Color.White.copy(alpha = 0.7f),
          disabledBorderColor = Color.White.copy(alpha = 0.35f),
          focusedLabelColor = Color(0xFFBAE6FD),
          unfocusedLabelColor = Color.White.copy(alpha = 0.82f),
          disabledLabelColor = Color.White.copy(alpha = 0.55f),
      )

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  Box(modifier = modifier.fillMaxSize()) {
    streamUiState.videoFrame?.let { videoFrame ->
      // Use key() to force recomposition when frame counter changes,
      // even if the bitmap reference is the same (due to caching optimization)
      key(streamUiState.videoFrameCount) {
        Image(
            bitmap = videoFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
      }
    }
    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    Column(
        modifier =
            Modifier.align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.86f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      OutlinedTextField(
          value = streamUiState.liveKitUrl,
          onValueChange = streamViewModel::updateLiveKitUrl,
          label = { Text("LiveKit URL") },
          singleLine = true,
          enabled = !streamUiState.isLiveKitConnected && !streamUiState.isLiveKitConnecting,
          keyboardOptions =
              KeyboardOptions(
                  capitalization = KeyboardCapitalization.None,
                  keyboardType = KeyboardType.Uri,
              ),
          colors = liveKitFieldColors,
          modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
          value = streamUiState.liveKitToken,
          onValueChange = streamViewModel::updateLiveKitToken,
          label = { Text("Publisher token") },
          singleLine = true,
          enabled = !streamUiState.isLiveKitConnected && !streamUiState.isLiveKitConnecting,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions =
              KeyboardOptions(
                  capitalization = KeyboardCapitalization.None,
                  keyboardType = KeyboardType.Password,
              ),
          colors = liveKitFieldColors,
          modifier = Modifier.fillMaxWidth(),
      )
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
            onClick = streamViewModel::fetchLiveKitToken,
            enabled =
                !streamUiState.isLiveKitConnected &&
                    !streamUiState.isLiveKitConnecting &&
                    !streamUiState.isLiveKitTokenFetching &&
                    streamUiState.liveKitUrl.isNotBlank(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
          Text("Token", maxLines = 1, overflow = TextOverflow.Clip)
        }
        Button(
            onClick = streamViewModel::connectLiveKit,
            enabled =
                !streamUiState.isLiveKitConnected &&
                    !streamUiState.isLiveKitConnecting &&
                    !streamUiState.isLiveKitTokenFetching &&
                    streamUiState.liveKitUrl.isNotBlank() &&
                    streamUiState.liveKitToken.isNotBlank(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
          Text("Connect", maxLines = 1, overflow = TextOverflow.Clip)
        }
        Button(
            onClick = streamViewModel::disconnectLiveKit,
            enabled = streamUiState.isLiveKitConnected || streamUiState.isLiveKitConnecting,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A1C1C)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
          Text("Stop LK", maxLines = 1, overflow = TextOverflow.Clip)
        }
      }
      Text(
          text = streamUiState.liveKitStatus,
          color = Color.White,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.fillMaxWidth(),
      )
    }

    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      Row(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth()
                  .height(56.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        SwitchButton(
            label = stringResource(R.string.stop_stream_button_title),
            onClick = {
              streamViewModel.stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            },
            isDestructive = true,
            modifier = Modifier.weight(1f),
        )

        // Photo capture button
        CaptureButton(
            onClick = { streamViewModel.capturePhoto() },
        )
      }
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}
