package com.locationjoystick.core.designsystem.component

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.locationjoystick.core.designsystem.LjIcons
import com.locationjoystick.core.model.SavedItemSortMode

@Composable
fun SavedItemSortMenu(
    selected: SavedItemSortMode,
    onSelected: (SavedItemSortMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(LjIcons.SwapVert, contentDescription = "Sort")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        SavedItemSortMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(mode.label) },
                leadingIcon = {
                    if (mode == selected) Icon(LjIcons.Check, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onSelected(mode)
                },
            )
        }
    }
}

private val SavedItemSortMode.label: String
    get() =
        when (this) {
            SavedItemSortMode.NAME_ASCENDING -> "A–Z"
            SavedItemSortMode.NAME_DESCENDING -> "Z–A"
            SavedItemSortMode.NEWEST_FIRST -> "Newest saved"
            SavedItemSortMode.OLDEST_FIRST -> "Oldest saved"
        }
