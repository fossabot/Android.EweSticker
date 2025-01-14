package com.fredhappyface.ewesticker

import android.content.ClipDescription
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.Build.VERSION.SDK_INT
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.gridlayout.widget.GridLayout
import androidx.preference.PreferenceManager
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.min

/**
 * ImageKeyboard class inherits from the InputMethodService class - provides the keyboard functionality
 */
class ImageKeyboard : InputMethodService() {

	// onCreate
	//    Constants
	private lateinit var internalDir: File
	private var totalIconPadding = 0

	//    Shared Preferences
	private lateinit var sharedPreferences: SharedPreferences
	private var vertical = false
	private var iconsPerX = 0
	private var iconSize = 0

	//    Load Packs
	private lateinit var loadedPacks: HashMap<String, StickerPack>
	private var activePack = ""

	//    Caches
	private var compatCache = Cache()
	private var recentCache = Cache()

	// onStartInput
	private lateinit var supportedMimes: List<String>

	// onCreateInputView
	private lateinit var keyboardRoot: ViewGroup
	private lateinit var packsList: ViewGroup
	private lateinit var packContent: ViewGroup
	private var keyboardHeight = 0
	private var fullIconSize = 0

	// switchPackLayout: cache for image container
	private var imageContainerCache = HashMap<Int, FrameLayout>()

	/**
	 * When the activity is created...
	 * - ensure coil can decode (and display) animated images
	 * - set the internal sticker dir, icon-padding, icon-size, icons-per-col, caches and loaded-packs
	 */
	override fun onCreate() {
		super.onCreate()
		val imageLoader = ImageLoader.Builder(baseContext)
			.componentRegistry {
				if (SDK_INT >= 28) {
					add(ImageDecoderDecoder(baseContext))
				} else {
					add(GifDecoder())
				}
			}
			.build()
		Coil.setImageLoader(imageLoader)
		//    Constants
		val scale = applicationContext.resources.displayMetrics.density
		this.internalDir = File(filesDir, "stickers")
		//    Shared Preferences
		this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(baseContext)
		this.vertical = this.sharedPreferences.getBoolean("vertical", false)
		this.iconsPerX = this.sharedPreferences.getInt("iconsPerX", 3)
		this.totalIconPadding =
			(resources.getDimension(R.dimen.sticker_padding) * 2 * (this.iconsPerX + 1)).toInt()
		this.iconSize = (if (this.vertical) {
			(resources.displayMetrics.widthPixels - this.totalIconPadding) / this.iconsPerX.toFloat()
		} else {
			(this.sharedPreferences.getInt("iconSize", 80) * scale)
		}).toInt()
		//    Load Packs
		this.loadedPacks = HashMap()
		val packs =
			this.internalDir.listFiles { obj: File ->
				obj.isDirectory && !obj.absolutePath.contains(
					"__compatSticker__"
				)
			}
				?: arrayOf()
		for (file in packs) {
			val pack = StickerPack(file)
			if (pack.stickerList.isNotEmpty()) {
				this.loadedPacks[file.name] = pack
			}
		}
		this.activePack = this.sharedPreferences.getString("activePack", "").toString()
		//    Caches
		this.sharedPreferences.getString("recentCache", "")
			?.let { this.recentCache.fromSharedPref(it) }
		this.sharedPreferences.getString("compatCache", "")
			?.let { this.compatCache.fromSharedPref(it) }
	}

	/**
	 * When the keyboard is first drawn...
	 * - inflate keyboardLayout
	 * - set the keyboard height
	 * - create pack icons
	 *
	 * @return View keyboardLayout
	 */
	override fun onCreateInputView(): View {
		val keyboardLayout =
			View.inflate(applicationContext, R.layout.keyboard_layout, null)
		this.keyboardRoot = keyboardLayout.findViewById(R.id.keyboardRoot)
		this.packsList = keyboardLayout.findViewById(R.id.packsList)
		this.packContent = keyboardLayout.findViewById(R.id.packContent)
		this.keyboardHeight = if (this.vertical) {
			800
		} else {
			this.iconSize * this.iconsPerX + this.totalIconPadding
		}
		this.packContent.layoutParams?.height = this.keyboardHeight
		this.fullIconSize = (min(
			resources.displayMetrics.widthPixels,
			this.keyboardHeight - resources.getDimensionPixelOffset(R.dimen.text_size_body)
		) * 0.95).toInt()
		createPackIcons()
		return keyboardLayout
	}

	/**
	 * Disable full-screen mode as content will likely be hidden by the IME.
	 *
	 * @return Boolean false
	 */
	override fun onEvaluateFullscreenMode(): Boolean {
		return false
	}

	/**
	 * When entering some input field update the list of supported-mimes
	 *
	 * @param info
	 * @param restarting
	 */
	override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
		this.supportedMimes =
			Utils.getSupportedMimes().filter { isCommitContentSupported(info, it) }
	}

	/**
	 * When leaving some input field update the caches
	 *
	 */
	override fun onFinishInput() {
		val editor = this.sharedPreferences.edit()
		editor.putString("recentCache", this.recentCache.toSharedPref())
		editor.putString("compatCache", this.compatCache.toSharedPref())
		editor.putString("activePack", this.activePack)
		editor.apply()
		super.onFinishInput()
	}

	/**
	 * In the event that a mimetype is unsupported by a InputConnectionCompat (looking at you,
	 * Signal) create a temporary png and send that. In the event that png is not supported,
	 * alert the user.
	 *
	 * @param file: File
	 */
	private suspend fun doFallbackCommitContent(file: File) {
		// PNG might not be supported
		if ("image/png" !in this.supportedMimes) {
			Toast.makeText(
				applicationContext,
				file.extension + " not supported here.", Toast.LENGTH_SHORT
			).show()
			return
		}
		// Create a new compatSticker and convert the sticker to png
		val compatStickerName = file.hashCode().toString()
		val compatSticker = File(this.internalDir, "__compatSticker__/$compatStickerName.png")
		if (!compatSticker.exists()) {
			// If the sticker doesn't exist then create
			compatSticker.parentFile?.mkdirs()
			try {
				val request = ImageRequest.Builder(baseContext).data(file).target { result ->
					val bitmap = result.toBitmap()
					bitmap.compress(Bitmap.CompressFormat.PNG, 90, FileOutputStream(compatSticker))
				}.build()
				imageLoader.execute(request)
			} catch (ignore: IOException) {
			}
		}
		// Send the compatSticker!
		doCommitContent("image/png", compatSticker)
		// Remove old stickers
		val remSticker = this.compatCache.add(compatStickerName)
		remSticker?.let { File(this.internalDir, "__compatSticker__/$remSticker.png").delete() }
	}

	/**
	 * Send a sticker file to a InputConnectionCompat
	 *
	 * @param mimeType:    String
	 * @param file:        File
	 */
	private fun doCommitContent(mimeType: String, file: File) {
		// ContentUri, ClipDescription, linkUri
		val inputContentInfoCompat = InputContentInfoCompat(
			FileProvider.getUriForFile(this, "com.fredhappyface.ewesticker.inputcontent", file),
			ClipDescription(file.name, arrayOf(mimeType)),
			null
		)
		// InputConnection, EditorInfo, InputContentInfoCompat, int flags, null opts
		InputConnectionCompat.commitContent(
			currentInputConnection, currentInputEditorInfo, inputContentInfoCompat,
			InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
		)
	}

	/**
	 * Check if the sticker is supported by the receiver
	 *
	 * @param editorInfo: EditorInfo - the editor/ receiver
	 * @param mimeType:   String - the image mimetype
	 * @return boolean - is the mimetype supported?
	 */
	private fun isCommitContentSupported(editorInfo: EditorInfo?, mimeType: String?): Boolean {
		editorInfo?.packageName ?: return false
		mimeType ?: return false
		currentInputConnection ?: return false
		for (supportedMimeType in EditorInfoCompat.getContentMimeTypes(editorInfo)) {
			if (ClipDescription.compareMimeTypes(mimeType, supportedMimeType)) {
				return true
			}
		}
		return false
	}

	/**
	 * Select the recent pack and create the pack layout
	 *
	 */
	private fun recentPackLayout() {
		val packLayout = createPackLayout(this.recentCache.toFiles().reversedArray())
		this.packContent.removeAllViewsInLayout()
		packLayout.parent ?: this.packContent.addView(packLayout)
		this.activePack = "__recentSticker__"
	}

	/**
	 * Swap the pack layout every time a pack is selected. If already cached use that
	 * otherwise create the pack layout
	 *
	 * @param pack StickerPack
	 */
	private fun switchPackLayout(pack: StickerPack) {
		// Check the cache
		this.activePack = pack.name
		val stickers = pack.stickerList
		val imageContainerHash = stickers.hashCode()
		lateinit var packLayout: FrameLayout
		if (imageContainerHash in imageContainerCache.keys) {
			packLayout = (imageContainerCache[imageContainerHash] ?: return)
		} else {
			packLayout = createPackLayout(stickers)
			imageContainerCache[imageContainerHash] = packLayout
		}
		// Swap the image container
		this.packContent.removeAllViewsInLayout()
		packLayout.parent ?: this.packContent.addView(packLayout)
	}

	/**
	 * Create the initial pack layout (the pack container and the grid)
	 *
	 * @return Pair<FrameLayout, GridLayout> packContainer to pack
	 */
	private fun createPartialPackLayout(): Pair<FrameLayout, GridLayout> {
		if (this.vertical) {
			val packContainer = layoutInflater.inflate(
				R.layout.pack_vertical, this.packContent, false
			) as FrameLayout
			val pack = packContainer.findViewById<GridLayout>(R.id.pack)
			pack.columnCount = this.iconsPerX
			return packContainer to pack
		}
		val packContainer = layoutInflater.inflate(
			R.layout.pack_horizontal,
			this.packContent,
			false
		) as FrameLayout
		val pack = packContainer.findViewById<GridLayout>(R.id.pack)
		pack.rowCount = this.iconsPerX
		return packContainer to pack
	}

	/**
	 * Create the pack layout (called by switchPackLayout if the FrameLayout is not cached)
	 *
	 * @param stickers
	 */
	private fun createPackLayout(stickers: Array<File>): FrameLayout {
		val (packContainer, pack) = createPartialPackLayout()
		for (sticker in stickers) {
			val imageCard = layoutInflater.inflate(
				R.layout.sticker_card,
				pack,
				false
			) as FrameLayout
			val imgButton = imageCard.findViewById<ImageButton>(R.id.stickerButton)
			imgButton.layoutParams.height = this.iconSize
			imgButton.layoutParams.width = this.iconSize
			imgButton.load(sticker)
			imgButton.tag = sticker
			imgButton.setOnClickListener {
				val file = it.tag as File
				this.recentCache.add(file.absolutePath)
				val stickerType = Utils.getMimeType(file)
				if (stickerType == null || stickerType !in this.supportedMimes) {
					CoroutineScope(Dispatchers.Main).launch {
						doFallbackCommitContent(file)
					}
					return@setOnClickListener
				}
				doCommitContent(stickerType, file)
			}
			imgButton.setOnLongClickListener { view: View ->
				val file = view.tag as File
				val fullSticker = layoutInflater.inflate(
					R.layout.sticker_preview,
					this.keyboardRoot,
					false
				) as RelativeLayout
				val fSticker = fullSticker.findViewById<ImageButton>(R.id.stickerButton)
				// Set dimens + load image
				fullSticker.layoutParams.height =
					this.keyboardHeight + (resources.getDimension(R.dimen.pack_dimens) + resources.getDimension(
						R.dimen.pack_padding_vertical
					) * 2).toInt()
				fSticker.layoutParams.height = this.fullIconSize
				fSticker.layoutParams.width = this.fullIconSize
				fSticker.load(file)
				// Tap to exit popup
				fullSticker.setOnClickListener { this.keyboardRoot.removeView(it) }
				fSticker.setOnClickListener { this.keyboardRoot.removeView(fullSticker) }
				this.keyboardRoot.addView(fullSticker)
				return@setOnLongClickListener true
			}
			pack.addView(imageCard)
		}
		return packContainer
	}

	private fun addPackButton(icon: Drawable? = null): ImageButton {
		val packCard = layoutInflater.inflate(R.layout.pack_card, this.packsList, false)
		val packButton = packCard.findViewById<ImageButton>(R.id.stickerButton)
		packButton.setImageDrawable(icon)
		this.packsList.addView(packCard)
		return packButton
	}

	/**
	 * Create the pack icons (image buttons) that when tapped switch the pack (switchPackLayout)
	 *
	 */
	private fun createPackIcons() {
		this.packsList.removeAllViewsInLayout()
		// Back button
		if (this.sharedPreferences.getBoolean("showBackButton", false)) {
			val backButton = addPackButton(
				ResourcesCompat.getDrawable(resources, R.drawable.ic_chevron_left, null)
			)
			backButton.setOnClickListener {
				if (SDK_INT >= 28) {
					this.switchToPreviousInputMethod()
				} else {
					(applicationContext.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
				}
			}
		}
		// Recent
		val recentButton =
			addPackButton(ResourcesCompat.getDrawable(resources, R.drawable.ic_clock, null))
		recentButton.setOnClickListener {
			recentPackLayout()
		}
		// Packs
		val sortedPackNames = this.loadedPacks.keys.toTypedArray()
		Arrays.sort(sortedPackNames)
		for (sortedPackName in sortedPackNames) {
			val pack = this.loadedPacks[sortedPackName] ?: return
			val packButton = addPackButton()
			packButton.load(pack.thumbSticker)
			packButton.tag = pack
			packButton.setOnClickListener { view: View? ->
				switchPackLayout(view?.tag as StickerPack)
			}
		}
		if (sortedPackNames.isNotEmpty()) {
			when (this.activePack) {
				"__recentSticker__" -> recentPackLayout()
				in sortedPackNames -> switchPackLayout(
					(this.loadedPacks[this.activePack] ?: return)
				)
				else -> switchPackLayout((this.loadedPacks[sortedPackNames[0]] ?: return))
			}
		}
	}
}
