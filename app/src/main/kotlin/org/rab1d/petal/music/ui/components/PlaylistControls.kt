/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.ui.PetalViewModel

@Composable
fun PlaylistNameDialog(
    title: String, initialName: String = "", onDismiss: () -> Unit,
    onSave: suspend (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("Playlist name") },
                singleLine = true, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch {
                try { onSave(name.trim()); onDismiss() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Couldn't save playlist" }
                finally { busy = false }
            }
        }) { Text(if (busy) "Saving…" else "Save") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CreatePlaylistButton(viewModel: PetalViewModel, source: Source) {
    var creating by remember { mutableStateOf(false) }
    TextButton(onClick = { creating = true }) { Text("New playlist") }
    if (creating) PlaylistNameDialog("New ${source.label} playlist", onDismiss = { creating = false }) {
        viewModel.createSourcePlaylist(source, it)
    }
}

@Composable
fun PlaylistActionsButton(
    viewModel: PetalViewModel, source: Source, id: String, name: String,
    onDeleted: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Box {
        IconButton(enabled = !busy, onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, "Playlist options")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Rename playlist") }, onClick = { expanded = false; renaming = true })
            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = {
                expanded = false
                busy = true
                scope.launch {
                    try { viewModel.deleteSourcePlaylist(source, id); onDeleted() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Toast.makeText(context, e.message ?: "Couldn't delete playlist", Toast.LENGTH_LONG).show() }
                    finally { busy = false }
                }
            })
        }
    }
    if (renaming) PlaylistNameDialog("Rename playlist", name, onDismiss = { renaming = false }) {
        viewModel.renameSourcePlaylist(source, id, it)
    }
}
