package com.famelack.tv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.famelack.tv.R
import com.famelack.tv.data.model.Channel

class ChannelAdapter(
    private val isFavoriteChecker: (String) -> Boolean,
    private val onChannelClick: (Channel, Int) -> Unit,
    private val onChannelLongClick: (Channel, Int) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_card, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)
        holder.bind(channel, isFavoriteChecker(channel.id))
    }

    override fun onViewRecycled(holder: ChannelViewHolder) {
        super.onViewRecycled(holder)
        holder.clearImage()
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivLogo: ImageView = itemView.findViewById(R.id.iv_channel_logo)
        private val tvInitials: TextView = itemView.findViewById(R.id.tv_channel_initials)
        private val tvName: TextView = itemView.findViewById(R.id.tv_channel_name)
        private val tvCategory: TextView = itemView.findViewById(R.id.tv_channel_category)
        private val tvResolution: TextView = itemView.findViewById(R.id.tv_channel_resolution)
        private val ivFavorite: ImageView = itemView.findViewById(R.id.iv_favorite_badge)

        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(pos), pos)
                }
            }

            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelLongClick(getItem(pos), pos)
                    notifyItemChanged(pos)
                    true
                } else {
                    false
                }
            }

            // High contrast TV D-pad focus feedback
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.05f else 1.0f
                v.scaleY = if (hasFocus) 1.05f else 1.0f
                v.elevation = if (hasFocus) 12f else 2f
            }
        }

        fun bind(channel: Channel, isFavorite: Boolean) {
            tvName.text = channel.name
            val subtitle = if (!channel.language.isNullOrEmpty()) "${channel.category} • ${channel.language}" else channel.category
            tvCategory.text = subtitle

            if (!channel.resolution.isNullOrEmpty()) {
                tvResolution.visibility = View.VISIBLE
                tvResolution.text = channel.resolution
            } else {
                tvResolution.visibility = View.GONE
            }

            ivFavorite.visibility = if (isFavorite) View.VISIBLE else View.GONE

            val initials = getInitials(channel.name)
            tvInitials.text = initials

            if (!channel.logo.isNullOrEmpty()) {
                ivLogo.visibility = View.VISIBLE
                tvInitials.visibility = View.GONE
                ivLogo.load(channel.logo) {
                    listener(
                        onError = { _, _ ->
                            ivLogo.visibility = View.GONE
                            tvInitials.visibility = View.VISIBLE
                        },
                        onSuccess = { _, _ ->
                            ivLogo.visibility = View.VISIBLE
                            tvInitials.visibility = View.GONE
                        }
                    )
                }
            } else {
                ivLogo.visibility = View.GONE
                tvInitials.visibility = View.VISIBLE
            }
        }

        fun clearImage() {
            ivLogo.dispose()
            ivLogo.setImageDrawable(null)
        }

        private fun getInitials(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "TV"
            val spaceIdx = trimmed.indexOf(' ')
            return if (spaceIdx != -1 && spaceIdx + 1 < trimmed.length) {
                "${trimmed[0]}${trimmed[spaceIdx + 1]}".uppercase()
            } else {
                trimmed.take(2).uppercase()
            }
        }
    }

    object ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}

