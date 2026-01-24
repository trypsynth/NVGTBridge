package com.nvgt.bridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.os.Bundle
import androidx.recyclerview.widget.RecyclerView

sealed class AppListItem {
	data class Header(val title: String) : AppListItem()
	data class App(val appInfo: AppInfo) : AppListItem()
}

class AppsAdapter(
	private var items: List<AppListItem>,
	private val onSwitchChanged: (AppInfo, Boolean) -> Unit,
	private val onAppLongClicked: (AppInfo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

	companion object {
		private const val VIEW_TYPE_HEADER = 0
		private const val VIEW_TYPE_APP = 1
	}

	override fun getItemViewType(position: Int): Int {
		return when (items[position]) {
			is AppListItem.Header -> VIEW_TYPE_HEADER
			is AppListItem.App -> VIEW_TYPE_APP
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		return if (viewType == VIEW_TYPE_HEADER) {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.item_header, parent, false)
			HeaderViewHolder(view)
		} else {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.item_app, parent, false)
			AppViewHolder(view)
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		when (val item = items[position]) {
			is AppListItem.Header -> {
				(holder as HeaderViewHolder).title.text = item.title
			}
			is AppListItem.App -> {
				val appHolder = holder as AppViewHolder
				val app = item.appInfo
				
				appHolder.appName.text = app.name
				appHolder.appIcon.setImageDrawable(app.icon)
				
				appHolder.appSwitch.setOnCheckedChangeListener(null)
				appHolder.appSwitch.isChecked = app.isEnabled

				appHolder.itemView.setOnClickListener {
					val newState = !app.isEnabled
					app.isEnabled = newState
					appHolder.appSwitch.isChecked = newState
					onSwitchChanged(app, newState)
				}
				
				appHolder.itemView.setOnLongClickListener {
					onAppLongClicked(app)
					true
				}

				ViewCompat.setAccessibilityDelegate(appHolder.itemView, object : AccessibilityDelegateCompat() {
					override fun onInitializeAccessibilityNodeInfo(
						host: View,
						info: AccessibilityNodeInfoCompat
					) {
						super.onInitializeAccessibilityNodeInfo(host, info)
						
						// Manually construct the content description to ensure "AppName, On/Off" order
						// and explicit state reporting on all Android versions.
						val stateText = if (app.isEnabled) "on" else "off"
						info.contentDescription = "${app.name}, $stateText"
						
						// Use Switch class name for the role announcement
						info.className = SwitchCompat::class.java.name
						
						// Disable isCheckable to prevent default "Checked/Ticked" announcements
						// which would duplicate our manual state text.
						info.isCheckable = false
						
						info.addAction(
							AccessibilityNodeInfoCompat.AccessibilityActionCompat(
								R.id.accessibility_action_configure,
								"Configure settings for ${app.name}"
							)
						)
						
						// Ensure click action is reported (important since isCheckable is false)
						info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
					}

					override fun performAccessibilityAction(
						host: View,
						action: Int,
						args: Bundle?
					): Boolean {
						if (action == R.id.accessibility_action_configure) {
							onAppLongClicked(app)
							return true
						}
						return super.performAccessibilityAction(host, action, args)
					}
				})
				
				appHolder.appSwitch.setOnClickListener {
					val newState = appHolder.appSwitch.isChecked
					app.isEnabled = newState
					onSwitchChanged(app, newState)
				}
			}
		}
	}

	override fun getItemCount(): Int {
		return items.size
	}

	fun updateItems(newItems: List<AppListItem>) {
		items = newItems
		notifyDataSetChanged()
	}

	class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		val appName: TextView = view.findViewById(R.id.app_name)
		val appIcon: ImageView = view.findViewById(R.id.app_icon)
		val appSwitch: SwitchCompat = view.findViewById(R.id.app_switch)
	}

	class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		val title: TextView = view.findViewById(R.id.header_title)
	}
}