package code.name.monkey.retromusic.activities.albums

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.transition.Slide
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.appthemehelper.util.ATHUtil
import code.name.monkey.appthemehelper.util.MaterialUtil
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsSlidingMusicPanelActivity
import code.name.monkey.retromusic.activities.tageditor.AbsTagEditorActivity
import code.name.monkey.retromusic.activities.tageditor.AlbumTagEditorActivity
import code.name.monkey.retromusic.adapter.album.HorizontalAlbumAdapter
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.dialogs.DeleteSongsDialog
import code.name.monkey.retromusic.extensions.extraNotNull
import code.name.monkey.retromusic.extensions.show
import code.name.monkey.retromusic.extensions.surfaceColor
import code.name.monkey.retromusic.glide.AlbumGlideRequest
import code.name.monkey.retromusic.glide.ArtistGlideRequest
import code.name.monkey.retromusic.glide.RetroMusicColoredTarget
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SortOrder.AlbumSongSortOrder
import code.name.monkey.retromusic.interfaces.CabHolder
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.network.model.LastFmAlbum
import code.name.monkey.retromusic.util.*
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.afollestad.materialcab.MaterialCab
import com.bumptech.glide.Glide
import kotlinx.android.synthetic.main.activity_album.*
import kotlinx.android.synthetic.main.activity_album_content.*
import org.koin.android.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import java.util.*
import android.util.Pair as UtilPair

class AlbumDetailsActivity : AbsSlidingMusicPanelActivity(), CabHolder {
    override fun openCab(menuRes: Int, callback: MaterialCab.Callback): MaterialCab {
        cab?.let {
            if (it.isActive) it.finish()
        }
        cab = MaterialCab(this, R.id.cab_stub)
            .setMenu(menuRes)
            .setCloseDrawableRes(R.drawable.ic_close)
            .setBackgroundColor(
                RetroColorUtil.shiftBackgroundColorForLightText(
                    ATHUtil.resolveColor(
                        this,
                        R.attr.colorSurface
                    )
                )
            )
            .start(callback)
        return cab as MaterialCab
    }

    private val detailsViewModel: AlbumDetailsViewModel by viewModel {
        parametersOf(extraNotNull<Int>(EXTRA_ALBUM_ID).value)
    }
    private lateinit var simpleSongAdapter: SimpleSongAdapter
    private lateinit var album: Album
    private var cab: MaterialCab? = null
    private val savedSortOrder: String
        get() = PreferenceUtil.albumDetailSongSortOrder

    override fun createContentView(): View {
        return wrapSlidingMusicPanel(R.layout.activity_album)
    }

    private fun windowEnterTransition() {
        val slide = Slide()
        slide.excludeTarget(R.id.appBarLayout, true)
        slide.excludeTarget(R.id.status_bar, true)
        slide.excludeTarget(android.R.id.statusBarBackground, true)
        slide.excludeTarget(android.R.id.navigationBarBackground, true)
        window.enterTransition = slide
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setDrawUnderStatusBar()
        super.onCreate(savedInstanceState)
        setStatusbarColorAuto()
        setNavigationbarColorAuto()
        setTaskDescriptionColorAuto()
        setLightNavigationBar(true)
        setBottomBarVisibility(View.GONE)
        window.sharedElementsUseOverlay = true
        windowEnterTransition()
        toolbar.setBackgroundColor(surfaceColor())

        addMusicServiceEventListener(detailsViewModel)
        ActivityCompat.postponeEnterTransition(this)

        detailsViewModel.getAlbum().observe(this, androidx.lifecycle.Observer {
            ActivityCompat.startPostponedEnterTransition(this@AlbumDetailsActivity)
            showAlbum(it)
        })
        detailsViewModel.getArtist().observe(this, androidx.lifecycle.Observer {
            loadArtistImage(it)
        })
        detailsViewModel.getMoreAlbums().observe(this, androidx.lifecycle.Observer {
            moreAlbums(it)
        })
        detailsViewModel.getAlbumInfo().observe(this, androidx.lifecycle.Observer {
            aboutAlbum(it)
        })
        setupRecyclerView()
        artistImage.setOnClickListener {
            val artistPairs = ActivityOptions.makeSceneTransitionAnimation(
                this,
                UtilPair.create(
                    artistImage,
                    getString(R.string.transition_artist_image)
                )
            )
            NavigationUtil.goToArtistOptions(this, album.artistId, artistPairs)
        }
        playAction.apply {
            setOnClickListener { MusicPlayerRemote.openQueue(album.songs!!, 0, true) }
        }
        shuffleAction.apply {
            setOnClickListener { MusicPlayerRemote.openAndShuffleQueue(album.songs!!, true) }
        }

        aboutAlbumText.setOnClickListener {
            if (aboutAlbumText.maxLines == 4) {
                aboutAlbumText.maxLines = Integer.MAX_VALUE
            } else {
                aboutAlbumText.maxLines = 4
            }
        }
    }

    private fun setupRecyclerView() {
        simpleSongAdapter = SimpleSongAdapter(this, ArrayList(), R.layout.item_song, this)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@AlbumDetailsActivity)
            itemAnimator = DefaultItemAnimator()
            isNestedScrollingEnabled = false
            adapter = simpleSongAdapter
        }
    }

    private fun showAlbum(album: Album) {
        if (album.songs!!.isEmpty()) {
            finish()
            return
        }
        this.album = album

        albumTitle.text = album.title
        val songText =
            resources.getQuantityString(
                R.plurals.albumSongs,
                album.songCount,
                album.songCount
            )
        songTitle.text = songText

        if (MusicUtil.getYearString(album.year) == "-") {
            albumText.text = String.format(
                "%s • %s",
                album.artistName,
                MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(album.songs))
            )
        } else {
            albumText.text = String.format(
                "%s • %s • %s",
                album.artistName,
                MusicUtil.getYearString(album.year),
                MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(album.songs))
            )
        }
        loadAlbumCover()
        simpleSongAdapter.swapDataSet(album.songs)
        detailsViewModel.loadArtist(album.artistId)
        detailsViewModel.loadAlbumInfo(album)
    }

    private fun moreAlbums(albums: List<Album>) {
        moreTitle.show()
        moreRecyclerView.show()
        moreTitle.text = String.format(getString(R.string.label_more_from), album.artistName)

        val albumAdapter = HorizontalAlbumAdapter(this, albums, null)
        moreRecyclerView.layoutManager = GridLayoutManager(
            this,
            1,
            GridLayoutManager.HORIZONTAL,
            false
        )
        moreRecyclerView.adapter = albumAdapter
    }

    private fun aboutAlbum(lastFmAlbum: LastFmAlbum) {
        if (lastFmAlbum.album != null) {
            if (lastFmAlbum.album.wiki != null) {
                aboutAlbumText.show()
                aboutAlbumTitle.show()
                aboutAlbumTitle.text =
                    String.format(getString(R.string.about_album_label), lastFmAlbum.album.name)
                aboutAlbumText.text = lastFmAlbum.album.wiki.content
            }
            if (lastFmAlbum.album.listeners.isNotEmpty()) {
                listeners.show()
                listenersLabel.show()
                scrobbles.show()
                scrobblesLabel.show()

                listeners.text = RetroUtil.formatValue(lastFmAlbum.album.listeners.toFloat())
                scrobbles.text = RetroUtil.formatValue(lastFmAlbum.album.playcount.toFloat())
            }
        }
    }

    private fun loadArtistImage(artist: Artist) {
        ArtistGlideRequest.Builder.from(Glide.with(this), artist)
            .generatePalette(this)
            .build()
            .dontAnimate()
            .dontTransform()
            .into(object : RetroMusicColoredTarget(artistImage) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                }
            })
    }

    private fun loadAlbumCover() {
        AlbumGlideRequest.Builder.from(Glide.with(this), album.safeGetFirstSong())
            .checkIgnoreMediaStore(this)
            .ignoreMediaStore(PreferenceUtil.isIgnoreMediaStoreArtwork)
            .generatePalette(this)
            .build()
            .dontAnimate()
            .dontTransform()
            .into(object : RetroMusicColoredTarget(image) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors)
                }
            })
    }

    private fun setColors(color: MediaNotificationProcessor) {
        MaterialUtil.tintColor(
            button = shuffleAction,
            textColor = color.primaryTextColor,
            backgroundColor = color.backgroundColor
        )
        MaterialUtil.tintColor(
            button = playAction,
            textColor = color.primaryTextColor,
            backgroundColor = color.backgroundColor
        )

        setSupportActionBar(toolbar)
        supportActionBar?.title = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_album_detail, menu)
        val sortOrder = menu.findItem(R.id.action_sort_order)
        setUpSortOrderMenu(sortOrder.subMenu)
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            this,
            toolbar,
            menu,
            getToolbarBackgroundColor(toolbar)
        )
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return handleSortOrderMenuItem(item)
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        var sortOrder: String? = null
        val songs = simpleSongAdapter.dataSet
        when (item.itemId) {
            R.id.action_play_next -> {
                MusicPlayerRemote.playNext(songs)
                return true
            }
            R.id.action_add_to_current_playing -> {
                MusicPlayerRemote.enqueue(songs)
                return true
            }
            R.id.action_add_to_playlist -> {
                AddToPlaylistDialog.create(songs).show(supportFragmentManager, "ADD_PLAYLIST")
                return true
            }
            R.id.action_delete_from_device -> {
                DeleteSongsDialog.create(songs).show(supportFragmentManager, "DELETE_SONGS")
                return true
            }
            android.R.id.home -> {
                super.onBackPressed()
                return true
            }
            R.id.action_tag_editor -> {
                val intent = Intent(this, AlbumTagEditorActivity::class.java)
                intent.putExtra(AbsTagEditorActivity.EXTRA_ID, album.id)
                val options = ActivityOptions.makeSceneTransitionAnimation(
                    this,
                    albumCoverContainer,
                    "${getString(R.string.transition_album_art)}_${album.id}"
                )
                startActivityForResult(
                    intent,
                    TAG_EDITOR_REQUEST, options.toBundle()
                )
                return true
            }
            /*Sort*/
            R.id.action_sort_order_title -> sortOrder = AlbumSongSortOrder.SONG_A_Z
            R.id.action_sort_order_title_desc -> sortOrder = AlbumSongSortOrder.SONG_Z_A
            R.id.action_sort_order_track_list -> sortOrder = AlbumSongSortOrder.SONG_TRACK_LIST
            R.id.action_sort_order_artist_song_duration ->
                sortOrder = AlbumSongSortOrder.SONG_DURATION
        }
        if (sortOrder != null) {
            item.isChecked = true
            setSaveSortOrder(sortOrder)
        }
        return true
    }

    private fun setUpSortOrderMenu(sortOrder: SubMenu) {
        when (savedSortOrder) {
            AlbumSongSortOrder.SONG_A_Z -> sortOrder.findItem(R.id.action_sort_order_title)
                .isChecked = true
            AlbumSongSortOrder.SONG_Z_A -> sortOrder.findItem(R.id.action_sort_order_title_desc)
                .isChecked = true
            AlbumSongSortOrder.SONG_TRACK_LIST -> sortOrder.findItem(R.id.action_sort_order_track_list)
                .isChecked = true
            AlbumSongSortOrder.SONG_DURATION -> sortOrder.findItem(R.id.action_sort_order_artist_song_duration)
                .isChecked = true
        }
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.albumDetailSongSortOrder = sortOrder
        when (sortOrder) {
            AlbumSongSortOrder.SONG_TRACK_LIST -> album.songs?.sortWith(Comparator { o1, o2 ->
                o1.trackNumber.compareTo(
                    o2.trackNumber
                )
            })
            AlbumSongSortOrder.SONG_A_Z -> album.songs?.sortWith(Comparator { o1, o2 ->
                o1.title.compareTo(
                    o2.title
                )
            })
            AlbumSongSortOrder.SONG_Z_A -> album.songs?.sortWith(Comparator { o1, o2 ->
                o2.title.compareTo(
                    o1.title
                )
            })
            AlbumSongSortOrder.SONG_DURATION -> album.songs?.sortWith(Comparator { o1, o2 ->
                o1.duration.compareTo(
                    o2.duration
                )
            })
        }
        album.songs?.let { simpleSongAdapter.swapDataSet(it) }
    }


    override fun onBackPressed() {
        if (cab != null && cab!!.isActive) {
            cab?.finish()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeMusicServiceEventListener(detailsViewModel)
    }

    companion object {

        const val EXTRA_ALBUM_ID = "extra_album_id"
        private const val TAG_EDITOR_REQUEST = 2001
    }
}