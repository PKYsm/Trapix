package com.trapix.app.ui.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.trapix.app.R
import com.trapix.app.data.model.IntruderLog
import com.trapix.app.databinding.ItemIntruderBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class IntruderAdapter(
    private val onItemClick: (IntruderLog) -> Unit,
    private val onItemLongClick: (IntruderLog) -> Unit
) : ListAdapter<IntruderLog, IntruderAdapter.ViewHolder>(DiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()

    inner class ViewHolder(val binding: ItemIntruderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: IntruderLog) {
            val file = File(log.imagePath)
            Glide.with(binding.ivThumb.context)
                .load(if (file.exists()) file else R.drawable.ic_broken_image)
                .centerCrop()
                .placeholder(R.drawable.bg_image_placeholder)
                .into(binding.ivThumb)

            val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            binding.tvTimestamp.text = sdf.format(Date(log.timestamp))
            binding.tvCamera.text = if (log.cameraUsed == "front") "📷 Front" else "📸 Rear"
            binding.tvAttempt.text = "Attempt #${log.attemptNumber}"

            val isSelected = selectedIds.contains(log.id)
            binding.cardItem.strokeWidth = if (isSelected) 3 else 0
            binding.ivSelected.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE

            binding.root.setOnClickListener { onItemClick(log) }
            binding.root.setOnLongClickListener {
                onItemLongClick(log)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemIntruderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id)
        else selectedIds.add(id)
        notifyDataSetChanged()
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()
    fun clearSelection() { selectedIds.clear(); notifyDataSetChanged() }
    fun isSelectionMode() = selectedIds.isNotEmpty()

    class DiffCallback : DiffUtil.ItemCallback<IntruderLog>() {
        override fun areItemsTheSame(oldItem: IntruderLog, newItem: IntruderLog) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: IntruderLog, newItem: IntruderLog) = oldItem == newItem
    }
}
