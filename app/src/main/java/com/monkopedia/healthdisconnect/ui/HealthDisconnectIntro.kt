package com.monkopedia.healthdisconnect.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme

@Composable
fun HealthDisconnectIntro(onClick: () -> Unit = {}) {
    val italicPrimary =
        SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    val boldSecondary =
        SpanStyle(color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Box(Modifier.padding(top = 64.dp)) {
            Icon(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(96.dp)
                    .align(Alignment.Center),
                painter = painterResource(id = R.drawable.ic_launcher_background),
                contentDescription = "Health Disconnect Icon",
                tint = MaterialTheme.colorScheme.tertiary
            )
            Icon(
                modifier = Modifier.size(128.dp),
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "Health Disconnect Icon",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            buildAnnotatedString {
                append("Welcome to\n")
                withStyle(style = boldSecondary) {
                    append("Health Disconnect")
                }
            },
            Modifier.padding(top = 32.dp),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Text(
            buildAnnotatedString {
                withStyle(style = boldSecondary) {
                    append("Health Disconnect")
                }
                append(" is an app to let you view the ")
                withStyle(style = italicPrimary) {
                    append("Health Connect")
                }
                append(
                    " data on your device. Not the world, not the internet, just you. This means that..."
                )
            },
            Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Column(Modifier.padding(24.dp)) {
            ExplanationRow(
                icon = R.drawable.baseline_cloud_off_24,
                text = buildAnnotatedString {
                    withStyle(style = boldSecondary) {
                        append("Health Disconnect")
                    }
                    append(" does ")
                    withStyle(style = italicPrimary) {
                        append("not")
                    }
                    append(
                        " have access to the internet. Ask one of your nerdy friends, " +
                            "they can check."
                    )
                }
            )
            ExplanationRow(
                modifier = Modifier.padding(top = 12.dp),
                icon = R.drawable.baseline_no_accounts_24,
                text = buildAnnotatedString {
                    withStyle(style = boldSecondary) {
                        append("Health Disconnect")
                    }
                    append(
                        " has no login or account required, nor allowed. " +
                            "Please keep your passwords to yourself."
                    )
                }
            )
            ExplanationRow(
                modifier = Modifier.padding(top = 12.dp),
                icon = R.drawable.baseline_do_not_touch_24,
                text = buildAnnotatedString {
                    withStyle(style = boldSecondary) {
                        append("Health Disconnect")
                    }
                    append(" will ")
                    withStyle(style = italicPrimary) {
                        append("never")
                    }
                    append(
                        " collect any of your data and will warn if you try to share any data outside the app."
                    )
                }
            )
        }
        Text(
            "With that out of the way, let's look at some data.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = onClick, Modifier.padding(16.dp)) {
            Text("Let's Go!", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ExplanationRow(modifier: Modifier = Modifier, @DrawableRes icon: Int, text: AnnotatedString) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(id = icon), contentDescription = "Explanation icon")
        Text(text, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
fun HealthDisconnectIntroPreview() {
    HealthDisconnectTheme(dynamicColor = false) {
        HealthDisconnectIntro()
    }
}
