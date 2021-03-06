package com.cookiejarapps.android.smartcookieweb.browser.home

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Display.FLAG_SECURE
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.room.Room
import com.cookiejarapps.android.smartcookieweb.BrowserActivity
import com.cookiejarapps.android.smartcookieweb.R
import com.cookiejarapps.android.smartcookieweb.addons.AddonsActivity
import com.cookiejarapps.android.smartcookieweb.browser.BrowsingMode
import com.cookiejarapps.android.smartcookieweb.browser.bookmark.ui.BookmarkFragment
import com.cookiejarapps.android.smartcookieweb.browser.shortcuts.ShortcutDatabase
import com.cookiejarapps.android.smartcookieweb.browser.shortcuts.ShortcutEntity
import com.cookiejarapps.android.smartcookieweb.browser.shortcuts.ShortcutGridAdapter
import com.cookiejarapps.android.smartcookieweb.ext.components
import com.cookiejarapps.android.smartcookieweb.ext.nav
import com.cookiejarapps.android.smartcookieweb.history.HistoryActivity
import com.cookiejarapps.android.smartcookieweb.preferences.UserPreferences
import com.cookiejarapps.android.smartcookieweb.settings.activity.SettingsActivity
import com.google.android.material.appbar.AppBarLayout
import kotlinx.android.synthetic.main.fragment_home.*
import kotlinx.android.synthetic.main.fragment_home.view.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.menu.view.MenuButton
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.toolbar.behavior.ToolbarPosition
import mozilla.components.lib.state.ext.consumeFlow
import mozilla.components.lib.state.ext.consumeFrom
import mozilla.components.support.ktx.android.content.getColorFromAttr
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifChanged
import mozilla.components.ui.tabcounter.TabCounterMenu
import org.mozilla.gecko.util.ThreadUtils
import java.lang.ref.WeakReference

@ExperimentalCoroutinesApi
class HomeFragment : Fragment() {
    private var database: ShortcutDatabase? = null

    private val args by navArgs<HomeFragmentArgs>()
    private lateinit var bundleArgs: Bundle

    private val browsingModeManager get() = (activity as BrowserActivity).browsingModeManager

    private val store: BrowserStore
        get() = components.store

    private var appBarLayout: AppBarLayout? = null

    @VisibleForTesting
    internal var getMenuButton: () -> MenuButton? = { menuButton }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bundleArgs = args.toBundle()
    }

    @Suppress("LongMethod")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        val activity = activity as BrowserActivity
        val components = requireContext().components

        updateLayout(view)

        if(!UserPreferences(requireContext()).showShortcuts){
            view.shortcut_name.visibility = View.GONE
            view.shortcut_grid.visibility = View.GONE
        }

        if(!UserPreferences(requireContext()).shortcutDrawerOpen){
            view.shortcut_name.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_shortcuts, 0, R.drawable.ic_baseline_chevron_up, 0)
            view.shortcut_grid.visibility = View.GONE
        }

        view.shortcut_name.setOnClickListener {
            if(UserPreferences(requireContext()).shortcutDrawerOpen){
                UserPreferences(requireContext()).shortcutDrawerOpen = false
                view.shortcut_name.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_shortcuts, 0, R.drawable.ic_baseline_chevron_up, 0)
                view.shortcut_grid.visibility = View.GONE
            }
            else{
                UserPreferences(requireContext()).shortcutDrawerOpen = true
                view.shortcut_name.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_shortcuts, 0, R.drawable.ic_baseline_chevron_down, 0)
                view.shortcut_grid.visibility = View.VISIBLE
            }
        }

        GlobalScope.launch {
            database = Room.databaseBuilder(
                requireContext(),
                ShortcutDatabase::class.java, "shortcut-database"
            ).build()

            val shortcutDao = database?.shortcutDao()
            val shortcuts: MutableList<ShortcutEntity> = shortcutDao?.getAll() as MutableList

            val adapter = ShortcutGridAdapter(requireContext(), getList(shortcuts))

            ThreadUtils.runOnUiThread {
                view.shortcut_grid.adapter = adapter
            }
        }

        view.shortcut_grid.setOnItemClickListener { _, _, position, _ ->
            if((view.shortcut_grid.adapter.getItem(position) as ShortcutEntity).add){
                showCreateShortcutDialog(view.shortcut_grid.adapter as ShortcutGridAdapter)
            }
            else{
                findNavController().navigate(
                    R.id.browserFragment
                )

                components.sessionUseCases.loadUrl(
                    (view.shortcut_grid.adapter.getItem(position) as ShortcutEntity).url!!
                )
            }
        }

        view.shortcut_grid.setOnItemLongClickListener { _, _, position, _ ->
            if(position == view.shortcut_grid.adapter.count - 1){
                return@setOnItemLongClickListener true
            }

            val items = arrayOf(resources.getString(R.string.edit_shortcut), resources.getString(R.string.delete_shortcut))

            AlertDialog.Builder(requireContext())
                .setTitle(resources.getString(R.string.edit_shortcut))
                .setItems(items) { _, which ->
                    when(which){
                        0 -> showEditShortcutDialog(position, view.shortcut_grid.adapter as ShortcutGridAdapter)
                        1 -> deleteShortcut(view.shortcut_grid.adapter.getItem(position) as ShortcutEntity, view.shortcut_grid.adapter as ShortcutGridAdapter)
                    }
                }
                .show()

            return@setOnItemLongClickListener true
        }

        view.privateBrowsingButton.setOnClickListener {
            if(browsingModeManager.mode == BrowsingMode.Private){
                browsingModeManager.mode = BrowsingMode.Normal
                view.homeLayout.background = ColorDrawable(requireContext().getColorFromAttr(R.attr.colorSurface))
            }
            else{
                browsingModeManager.mode = BrowsingMode.Private
                view.homeLayout.background = resources.getDrawable(R.drawable.private_background)
            }
        }

        appBarLayout = view.homeAppBar

        return view
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        getMenuButton()?.dismissMenu()
    }

    override fun onResume() {
        super.onResume()

        if (browsingModeManager.mode == BrowsingMode.Private) {
            view?.homeLayout?.background = resources.getDrawable(R.drawable.private_background)
        }
    }

    private fun getList(shortcutEntity: MutableList<ShortcutEntity>): MutableList<ShortcutEntity> {
        shortcutEntity.add(shortcutEntity.size, ShortcutEntity(url = "test", add = true))
        return shortcutEntity
    }

    private fun showEditShortcutDialog(position: Int, adapter: ShortcutGridAdapter){
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
        builder.setTitle(resources.getString(R.string.edit_shortcut))
        val viewInflated: View = LayoutInflater.from(context).inflate(R.layout.add_shortcut_dialog, view as ViewGroup?, false)
        val input = viewInflated.findViewById<View>(R.id.urlEditText) as EditText
        input.setText(adapter.list[position].url)
        builder.setView(viewInflated)

        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            val item = adapter.list[position]
            item.url = input.text.toString()
            adapter.notifyDataSetChanged()

            GlobalScope.launch {
                database?.shortcutDao()?.update(item)
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }

        builder.show()
    }

    private fun showCreateShortcutDialog(adapter: ShortcutGridAdapter){
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
        builder.setTitle(resources.getString(R.string.add_shortcut))
        val viewInflated: View = LayoutInflater.from(context).inflate(R.layout.add_shortcut_dialog, view as ViewGroup?, false)
        val input = viewInflated.findViewById<View>(R.id.urlEditText) as EditText
        builder.setView(viewInflated)

        builder.setPositiveButton(android.R.string.ok) { dialog, _ ->
            dialog.dismiss()
            val list = adapter.list
            list.add(ShortcutEntity(url = input.text.toString()))
            list.removeAt(adapter.list.size - 2)
            adapter.list = getList(list)
            adapter.notifyDataSetChanged()

            GlobalScope.launch {
                database?.shortcutDao()?.insertAll(ShortcutEntity(url = input.text.toString()))
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }

        builder.show()
    }

    private fun deleteShortcut(shortcutEntity: ShortcutEntity, adapter: ShortcutGridAdapter) {
        val list = adapter.list
        list.remove(shortcutEntity)
        adapter.list = list
        adapter.notifyDataSetChanged()

        GlobalScope.launch {
            database?.shortcutDao()?.delete(shortcutEntity)
        }
    }

    private fun updateLayout(view: View) {
        when (UserPreferences(view.context).toolbarPosition) {
            ToolbarPosition.TOP.ordinal -> {
                view.toolbarLayout.layoutParams = CoordinatorLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP
                }

                ConstraintSet().apply {
                    clone(view.toolbarLayout)
                    clear(view.bottom_bar.id, BOTTOM)
                    clear(view.bottomBarShadow.id, BOTTOM)
                    connect(view.bottom_bar.id, TOP, PARENT_ID, TOP)
                    connect(view.bottomBarShadow.id, TOP, view.bottom_bar.id, BOTTOM)
                    connect(view.bottomBarShadow.id, BOTTOM, PARENT_ID, BOTTOM)
                    applyTo(view.toolbarLayout)
                }

                view.homeAppBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin =
                        resources.getDimensionPixelSize(R.dimen.home_fragment_top_toolbar_header_margin)
                }
            }
            ToolbarPosition.BOTTOM.ordinal -> {
            }
        }
    }

    @Suppress("LongMethod", "ComplexMethod")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeSearchEngineChanges()
        createHomeMenu(requireContext(), WeakReference(view.menuButton))
        createTabCounterMenu(view)

        view.menuButton.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                R.color.main_icon
            )
        )

        view.toolbar_wrapper.setOnClickListener {
            navigateToSearch()
        }

        view.tab_button.setOnClickListener {
            openTabDrawer()
        }

        if (browsingModeManager.mode.isPrivate) {
            requireActivity().window.addFlags(FLAG_SECURE)
        } else {
            requireActivity().window.clearFlags(FLAG_SECURE)
        }

        consumeFrom(components.store) {
            updateTabCounter(it)
        }

        updateTabCounter(components.store.state)

        if (bundleArgs.getBoolean(FOCUS_ON_ADDRESS_BAR)) {
            navigateToSearch()
        }
    }

    private fun observeSearchEngineChanges() {
        consumeFlow(store) { flow ->
            flow.map { state -> state.search.selectedOrDefaultSearchEngine }
                .ifChanged()
                .collect { searchEngine ->
                    if (searchEngine != null) {
                        val iconSize =
                            requireContext().resources.getDimensionPixelSize(R.dimen.icon_width)
                        val searchIcon =
                            BitmapDrawable(requireContext().resources, searchEngine.icon)
                        searchIcon.setBounds(0, 0, iconSize, iconSize)
                        search_engine_icon?.setImageDrawable(searchIcon)
                    } else {
                        search_engine_icon.setImageDrawable(null)
                    }
                }
        }
    }

    private fun createTabCounterMenu(view: View) {
        val browsingModeManager = (activity as BrowserActivity).browsingModeManager
        val mode = browsingModeManager.mode

        val onItemTapped: (TabCounterMenu.Item) -> Unit = {
            if (it is TabCounterMenu.Item.NewTab) {
                browsingModeManager.mode = BrowsingMode.Normal
            } else if (it is TabCounterMenu.Item.NewPrivateTab) {
                browsingModeManager.mode = BrowsingMode.Private
            }
        }

        view.tab_button.setOnLongClickListener {
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        appBarLayout = null
        bundleArgs.clear()
        requireActivity().window.clearFlags(FLAG_SECURE)
    }

    private fun navigateToSearch() {
        val directions =
            HomeFragmentDirections.actionGlobalSearchDialog(
                sessionId = null
            )

        // TODO: OPTIONS
        nav(R.id.homeFragment, directions, null)
    }

    @SuppressWarnings("ComplexMethod", "LongMethod")
    private fun createHomeMenu(context: Context, menuButtonView: WeakReference<MenuButton>) =
        HomeMenu(
            this.viewLifecycleOwner,
            context,
            onItemTapped = {
                when (it) {
                    HomeMenu.Item.Settings -> {
                        val settings = Intent(activity, SettingsActivity::class.java)
                        settings.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        requireActivity().startActivity(settings)
                    }
                    HomeMenu.Item.Bookmarks -> {
                        val drawerLayout = activity?.findViewById<DrawerLayout>(R.id.drawer_layout)
                        val bookmarksDrawer = if(UserPreferences(requireContext()).swapDrawers) requireActivity().findViewById<FrameLayout>(R.id.left_drawer) else requireActivity().findViewById<FrameLayout>(R.id.right_drawer)

                        if (bookmarksDrawer != null) {
                            drawerLayout?.openDrawer(bookmarksDrawer)
                        }
                    }
                    HomeMenu.Item.History -> {
                        val settings = Intent(activity, HistoryActivity::class.java)
                        settings.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        activity?.startActivity(settings)
                    }
                    HomeMenu.Item.AddonsManager -> {
                        val settings = Intent(activity, AddonsActivity::class.java)
                        settings.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        activity?.startActivity(settings)
                    }
                }
            },
            onHighlightPresent = { menuButtonView.get()?.setHighlight(it) },
            onMenuBuilderChanged = { menuButtonView.get()?.menuBuilder = it }
        )

    private fun openTabDrawer() {
        val drawerLayout = activity?.findViewById<DrawerLayout>(R.id.drawer_layout)
        val tabDrawer = if(UserPreferences(requireContext()).swapDrawers) requireActivity().findViewById<FrameLayout>(R.id.right_drawer) else requireActivity().findViewById<FrameLayout>(R.id.left_drawer)

        if (tabDrawer != null) {
            drawerLayout?.openDrawer(tabDrawer)
        }
    }

    private fun updateTabCounter(browserState: BrowserState) {
        val tabCount = if (browsingModeManager.mode.isPrivate) {
            browserState.privateTabs.size
        } else {
            browserState.normalTabs.size
        }

        view?.tab_button?.setCountWithAnimation(tabCount)
    }

    companion object {
        private const val FOCUS_ON_ADDRESS_BAR = "focusOnAddressBar"
    }
}
