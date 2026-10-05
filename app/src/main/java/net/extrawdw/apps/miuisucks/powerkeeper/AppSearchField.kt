package net.extrawdw.apps.miuisucks.powerkeeper

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/** Material's native racetrack-shaped search input, filtering the visible Apps list in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSearchField(query: String, onQueryChanged: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    SearchBarDefaults.InputField(
        query = query,
        onQueryChange = onQueryChanged,
        onSearch = { focusManager.clearFocus() },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier.testTag("apps-search"),
        placeholder = { Text(stringResource(R.string.apps_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChanged("") }) {
                Icon(Icons.Default.Close, stringResource(R.string.clear))
            }
        },
    )
}
