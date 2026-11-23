package com.osei.aimusicstudio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osei.aimusicstudio.ui.theme.*

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextWhite)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "About Osei AI Music Studio",
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = CardSurface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(56.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Osei AI Music Studio v1.0", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Professional AI music production, stem separation, vocal separation, song analysis, and multitrack mixing. Built with Kotlin, Jetpack Compose, Room, Media3, and ONNX Runtime.",
                    color = TextGrey,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Open-Source Model Attribution",
                    color = NeonBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = com.osei.aimusicstudio.ai.SeparationModelCatalog.UVR_MIT_ATTRIBUTION +
                        "\nModels: UVR-MDX-NET-Inst_HQ_4.onnx, UVR-MDX-NET-Inst_HQ_3.onnx, UVR_MDXNET_Main.onnx (TRvlvr/model_repo).",
                    color = TextGrey,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
