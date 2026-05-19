package me.magnum.melonds.ui.romlist

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import me.magnum.melonds.domain.model.RomScanningStatus
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.impl.RomSaveFileManager
import me.magnum.melonds.parcelables.RomParcelable
import me.magnum.melonds.R
import me.magnum.melonds.ui.romdetails.RomDetailsActivity
import me.magnum.melonds.ui.romlist.composables.RomBrowserScreen
import me.magnum.melonds.ui.romlist.composables.RomContextMenu
import me.magnum.melonds.ui.theme.MelonTheme

@AndroidEntryPoint
class RomListFragment : Fragment() {
    companion object {
        private const val KEY_ALLOW_ROM_CONFIGURATION = "allow_rom_configuration"
        private const val KEY_ROM_ENABLE_CRITERIA = "rom_enable_criteria"

        fun newInstance(allowRomConfiguration: Boolean, enableCriteria: RomEnableCriteria): RomListFragment {
            return RomListFragment().also {
                it.arguments = bundleOf(
                    KEY_ALLOW_ROM_CONFIGURATION to allowRomConfiguration,
                    KEY_ROM_ENABLE_CRITERIA to enableCriteria.toString(),
                )
            }
        }
    }

    enum class RomEnableCriteria {
        ENABLE_ALL,
        ENABLE_NON_DSIWARE,
    }

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var romSaveFileManager: RomSaveFileManager

    private val romListViewModel: RomListViewModel by activityViewModels()
    private lateinit var backPressedCallback: OnBackPressedCallback

    private var romSelectedListener: ((Rom) -> Unit)? = null
    private var pendingSaveImportRom: Rom? = null

    private val saveFileImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val rom = pendingSaveImportRom
        pendingSaveImportRom = null

        if (uri != null && rom != null) {
            validateAndConfirmSaveImport(rom, uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val allowRomConfiguration = arguments?.getBoolean(KEY_ALLOW_ROM_CONFIGURATION) ?: true

        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                romListViewModel.navigateUp()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MelonTheme {
                    val state by romListViewModel.browserState.collectAsState()
                    val scanningStatus by romListViewModel.romScanningStatus
                        .collectAsState(initial = RomScanningStatus.NOT_SCANNING)
                    val confirmedAchievementHashes by romListViewModel.confirmedAchievementHashes.collectAsState()
                    val raCoverByHash by romListViewModel.raCoverByHash.collectAsState()
                    var contextRomUri by remember { mutableStateOf<String?>(null) }

                    backPressedCallback.isEnabled = state.canNavigateUp && !state.isSearchActive

                    val currentContextRom: Rom? = remember(contextRomUri, state.entries, state.continuePlaying) {
                        val target = contextRomUri ?: return@remember null
                        state.entries.firstNotNullOfOrNull {
                            (it as? RomBrowserEntry.RomItem)?.rom?.takeIf { r -> r.uri.toString() == target }
                        } ?: state.continuePlaying.firstOrNull { it.uri.toString() == target }
                    }

                    RomBrowserScreen(
                        state = state,
                        coverByHash = raCoverByHash,
                        allowConfiguration = allowRomConfiguration,
                        scanningStatus = scanningStatus,
                        confirmedAchievementHashes = confirmedAchievementHashes,
                        onFolderClick = { folder -> romListViewModel.openFolder(folder.docId) },
                        onRomClick = { rom ->
                            romListViewModel.setRomLastPlayedNow(rom)
                            romSelectedListener?.invoke(rom)
                        },
                        onRomLongPress = { rom -> contextRomUri = rom.uri.toString() },
                        onRomConfigClick = { rom -> openRomDetails(rom) },
                        onFilterSelected = { filter -> romListViewModel.setFilter(filter) },
                        onNavigateUp = { romListViewModel.navigateUp() },
                        onRefresh = { romListViewModel.refreshRoms() },
                        onDpadDownGateChanged = { gate ->
                            (activity as? RomListActivity)?.setRomBrowserDpadDownGate(gate)
                        },
                    )

                    RomContextMenu(
                        rom = currentContextRom,
                        onDismiss = { contextRomUri = null },
                        onToggleFavorite = { rom -> romListViewModel.toggleFavorite(rom) },
                        onShowDetails = { rom -> openRomDetails(rom) },
                        onSendSaveFile = { rom -> shareSaveFile(rom) },
                        onImportSaveFile = { rom -> requestSaveFileImport(rom) },
                    )
                }
            }
        }
    }

    private fun openRomDetails(rom: Rom) {
        val intent = Intent(requireContext(), RomDetailsActivity::class.java).apply {
            putExtra(RomDetailsActivity.KEY_ROM, RomParcelable(rom))
        }
        startActivity(intent)
    }

    private fun shareSaveFile(rom: Rom) {
        viewLifecycleOwner.lifecycleScope.launch {
            val sharedSaveFile = runCatching {
                withContext(Dispatchers.IO) {
                    romSaveFileManager.prepareShareFile(rom)
                }
            }.getOrElse {
                Toast.makeText(requireContext(), R.string.rom_save_file_share_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            if (sharedSaveFile == null) {
                Toast.makeText(requireContext(), R.string.rom_save_file_missing, Toast.LENGTH_LONG).show()
                return@launch
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, sharedSaveFile.uri)
                putExtra(Intent.EXTRA_TITLE, sharedSaveFile.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(requireContext().contentResolver, sharedSaveFile.fileName, sharedSaveFile.uri)
            }
            val chooser = Intent.createChooser(shareIntent, getString(R.string.rom_save_file_share_chooser))
            runCatching { startActivity(chooser) }
                .onFailure {
                    Toast.makeText(requireContext(), R.string.rom_save_file_share_failed, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun requestSaveFileImport(rom: Rom) {
        pendingSaveImportRom = rom
        saveFileImportLauncher.launch(arrayOf("*/*"))
    }

    private fun validateAndConfirmSaveImport(rom: Rom, sourceUri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val isPlausibleSaveFile = runCatching {
                withContext(Dispatchers.IO) {
                    romSaveFileManager.isPlausibleSaveFile(sourceUri)
                }
            }.getOrDefault(false)

            if (!isPlausibleSaveFile) {
                Toast.makeText(requireContext(), R.string.rom_save_file_import_invalid, Toast.LENGTH_LONG).show()
                return@launch
            }

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.rom_save_file_import_title)
                .setMessage(getString(R.string.rom_save_file_import_message, rom.config.customName ?: rom.name))
                .setPositiveButton(android.R.string.ok) { _, _ -> importSaveFile(rom, sourceUri) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun importSaveFile(rom: Rom, sourceUri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    romSaveFileManager.importSaveFile(rom, sourceUri)
                }
            }
            val message = if (result.isSuccess) {
                R.string.rom_save_file_import_success
            } else {
                R.string.rom_save_file_import_failed
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    fun setRomSelectedListener(listener: (Rom) -> Unit) {
        romSelectedListener = listener
    }
}
