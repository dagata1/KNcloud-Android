package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.v2ray.ang.R
import com.v2ray.ang.databinding.DialogNodeSelectorBinding
import com.v2ray.ang.databinding.ItemNodeSelectorBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.viewmodel.MainViewModel

class NodeSelectorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogNodeSelectorBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()
    private val nodeAdapter by lazy { NodeSelectorAdapter() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogNodeSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Expand bottom sheet smoothly
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as? BottomSheetDialog
            val bottomSheet = bottomSheetDialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        binding.btnSheetClose.setOnClickListener {
            dismiss()
        }

        binding.btnSheetTestAll.setOnClickListener {
            (activity as? MainActivity)?.realPingAll()
        }

        binding.recyclerViewNodes.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = nodeAdapter
        }

        updateList()

        mainViewModel.updateListAction.observe(viewLifecycleOwner) {
            updateList()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun updateList() {
        val count = mainViewModel.serversCache.size
        binding.tvSheetEmpty.isVisible = count == 0
        binding.recyclerViewNodes.isVisible = count > 0
        nodeAdapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class NodeSelectorAdapter : RecyclerView.Adapter<NodeSelectorAdapter.ViewHolder>() {

        override fun getItemCount(): Int = mainViewModel.serversCache.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemNodeSelectorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = mainViewModel.serversCache[position]
            val guid = item.guid
            val profile = item.profile
            val isSelected = (guid == MmkvManager.getSelectServer())
            val density = resources.displayMetrics.density

            // Node Name & Address
            holder.binding.tvName.text = profile.remarks.ifBlank { getString(R.string.app_name) }
            holder.binding.tvType.text = profile.configType.name
            holder.binding.tvAddress.text = formatNodeAddress(profile)

            // Ping Delay
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            val delayMillis = aff?.testDelayMillis ?: 0L
            val delayStr = aff?.getTestDelayString().orEmpty()

            if (delayStr.isNotBlank()) {
                holder.binding.tvPing.isVisible = true
                if (delayMillis < 0L) {
                    holder.binding.tvPing.text = "error"
                    holder.binding.tvPing.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPingRed))
                } else {
                    holder.binding.tvPing.text = delayStr
                    when {
                        delayMillis in 1..150 -> {
                            holder.binding.tvPing.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPingGreen))
                        }
                        delayMillis in 151..300 -> {
                            holder.binding.tvPing.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPingYellow))
                        }
                        else -> {
                            holder.binding.tvPing.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPingRed))
                        }
                    }
                }
            } else {
                holder.binding.tvPing.isVisible = false
            }

            // Selection UI styling
            if (isSelected) {
                holder.binding.cardNode.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_node_bg_selected))
                holder.binding.cardNode.strokeColor = ContextCompat.getColor(requireContext(), R.color.card_node_stroke_selected)
                holder.binding.cardNode.strokeWidth = (1.5f * density).toInt()
                holder.binding.ivCheckSelected.isVisible = true
            } else {
                holder.binding.cardNode.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_node_bg))
                holder.binding.cardNode.strokeColor = ContextCompat.getColor(requireContext(), R.color.card_node_stroke)
                holder.binding.cardNode.strokeWidth = (1f * density).toInt()
                holder.binding.ivCheckSelected.isVisible = false
            }

            // Click listener to select node
            holder.binding.layoutNodeContainer.setOnClickListener {
                (activity as? MainActivity)?.onNodeSelected(guid)
                dismiss()
            }
        }

        private fun formatNodeAddress(profile: ProfileItem): String {
            return "${
                profile.server?.let {
                    if (it.contains(":"))
                        it.split(":").take(2).joinToString(":", postfix = ":***")
                    else
                        it.split('.').dropLast(1).joinToString(".", postfix = ".***")
                } ?: ""
            } : ${profile.serverPort ?: ""}"
        }

        inner class ViewHolder(val binding: ItemNodeSelectorBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "NodeSelectorBottomSheet"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            val sheet = NodeSelectorBottomSheet()
            sheet.show(fragmentManager, TAG)
        }
    }
}
