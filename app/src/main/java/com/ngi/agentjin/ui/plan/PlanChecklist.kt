package com.ngi.agentjin.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.agentjin.core.planning.StepStatus
import com.ngi.agentjin.core.planning.TaskPlan
import com.ngi.agentjin.ui.theme.Gold
import com.ngi.agentjin.ui.theme.PurpleMid

@Composable
fun PlanChecklist(plan: TaskPlan, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PurpleMid)) {
        Column(Modifier.padding(12.dp)) {
            Text("Plan r${plan.revision} · ${plan.goal.take(80)}", style = MaterialTheme.typography.titleSmall, color = Gold)
            plan.steps.forEach { step ->
                val icon = when (step.status) {
                    StepStatus.DONE -> Icons.Outlined.CheckCircle
                    StepStatus.FAILED -> Icons.Outlined.ErrorOutline
                    StepStatus.IN_PROGRESS -> Icons.Outlined.HourglassEmpty
                    else -> Icons.Outlined.RadioButtonUnchecked
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, step.status.name)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("${step.id}. ${step.description.ifBlank { step.plugin }}", style = MaterialTheme.typography.bodyMedium)
                        if (!step.resultMessage.isNullOrBlank()) {
                            Text(step.resultMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
