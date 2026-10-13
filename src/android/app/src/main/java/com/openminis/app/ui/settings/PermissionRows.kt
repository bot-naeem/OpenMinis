package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.offload.OffloadPermissionManager.PermissionLevel

/**
 * [T-android-permissions-redesign] A capability row whose control is a switch
 * plus one secondary switch, not a three-value dropdown.
 *
 * The dropdown asked the user to pick from three states when two of them were
 * equivalent from the row's point of view, and its default value — BYPASS — was
 * what fourteen of the eighteen capabilities ship with. So for almost every row
 * the control's whole job was to render the word "Bypass" behind a chevron.
 *
 * A switch answers the question the row is actually asking: may the agent do
 * this at all. The secondary switch only appears once the answer is yes, so
 * "ask me every time" is reachable without being a peer of "off" in a menu.
 *
 * The mapping is exact rather than lossy:
 *   allowed + ask    -> ASK_ONCE
 *   allowed + no ask -> BYPASS
 *   off              -> NOT_ALLOWED
 */
@Composable
fun CapabilityRow(
    title: String,
    subtitle: String?,
    level: PermissionLevel,
    onLevelChange: (PermissionLevel) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    statusText: String? = null,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val allowed = level != PermissionLevel.NOT_ALLOWED
    val askFirst = level == PermissionLevel.ASK_ONCE

    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = onClick,
        showDivider = showDivider,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                statusText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                SettingsSwitch(
                    checked = allowed,
                    onCheckedChange = { on ->
                        onLevelChange(
                            if (on) PermissionLevel.BYPASS else PermissionLevel.NOT_ALLOWED
                        )
                    },
                )
            }
        },
    )

    // Only meaningful once the capability is reachable at all: "ask me every
    // time" is not a state something switched-off can be in.
    AnimatedVisibility(visible = allowed && askFirst) {
        SettingsSwitchRow(
            title = stringResource(R.string.perm_ask_each_time),
            subtitle = stringResource(R.string.perm_ask_each_time_sub),
            checked = true,
            onCheckedChange = { on -> if (!on) onLevelChange(PermissionLevel.BYPASS) },
            showDivider = showDivider,
        )
    }
}

/**
 * A collapsed group.
 *
 * The whole point of the redesign: a fresh install shows three rows instead of
 * sixteen, because fourteen capabilities ship allowed and need no decision. They
 * live here until the user opens the group.
 */
@Composable
fun CollapsibleCapabilityGroup(
    title: String,
    summary: String,
    footer: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    SettingsSection(header = null, footer = footer) {
        SettingsRow(
            title = title,
            subtitle = summary,
            icon = icon,
            iconColor = tint,
            onClick = onToggle,
            showChevron = false,
            showDivider = false,
            trailing = {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
            },
        )
        AnimatedVisibility(visible = expanded) {
            Column { content() }
        }
    }
}

/** Empty state for the "what you changed" group. */
@Composable
fun NoRestrictionsRow() {
    SettingsRow(
        title = stringResource(R.string.perm_no_restrictions),
        subtitle = stringResource(R.string.perm_no_restrictions_sub),
        showChevron = false,
        showDivider = false,
    )
}

/** One row standing in for N grants that are all already fine. */
@Composable
fun AllHealthyRow(label: String, count: Int) {
    SettingsRow(
        title = label,
        subtitle = stringResource(R.string.perm_all_healthy_sub, count),
        icon = Icons.Outlined.ExpandMore,
        iconColor = Color(0xFF34C759),
        showChevron = false,
        showDivider = false,
    )
}
