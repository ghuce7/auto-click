package com.personal.autoclicker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.personal.autoclicker.databinding.ItemPresetBinding
import com.personal.autoclicker.model.Preset

class PresetAdapter(
    private val onRename: (Preset) -> Unit,
    private val onDelete: (Preset) -> Unit
) : RecyclerView.Adapter<PresetAdapter.PresetViewHolder>() {

    private var items: List<Preset> = emptyList()

    fun submit(newItems: List<Preset>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
        val binding = ItemPresetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PresetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PresetViewHolder(private val binding: ItemPresetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(preset: Preset) {
            binding.tvPresetName.text = preset.name
            binding.tvPresetInfo.text =
                binding.root.context.getString(R.string.preset_info, preset.steps.size)
            binding.btnRename.setOnClickListener { onRename(preset) }
            binding.btnDelete.setOnClickListener { onDelete(preset) }
        }
    }
}
