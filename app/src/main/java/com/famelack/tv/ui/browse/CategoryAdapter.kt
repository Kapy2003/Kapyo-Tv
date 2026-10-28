package com.famelack.tv.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.famelack.tv.R
import com.famelack.tv.data.model.Category

class CategoryAdapter(
    private val backgroundRes: Int = R.drawable.bg_chip_category,
    private val onCategorySelected: (Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private var categories: List<Category> = emptyList()
    private var selectedCategoryId: String = Category.ID_ALL

    fun submitList(newList: List<Category>, selectedId: String = selectedCategoryId) {
        this.categories = newList
        this.selectedCategoryId = selectedId
        notifyDataSetChanged()
    }

    fun setSelectedCategory(categoryId: String) {
        if (selectedCategoryId != categoryId) {
            val oldIdx = categories.indexOfFirst { it.id == selectedCategoryId }
            val newIdx = categories.indexOfFirst { it.id == categoryId }
            selectedCategoryId = categoryId
            if (oldIdx != -1) notifyItemChanged(oldIdx)
            if (newIdx != -1) notifyItemChanged(newIdx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category_chip, parent, false)
        view.setBackgroundResource(backgroundRes)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        val category = categories[position]
        holder.bind(category, category.id == selectedCategoryId)
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_category_name)
        private val tvCount: TextView = itemView.findViewById(R.id.tv_category_count)

        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val cat = categories[pos]
                    setSelectedCategory(cat.id)
                    onCategorySelected(cat)
                }
            }
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.08f else 1.0f
                v.scaleY = if (hasFocus) 1.08f else 1.0f
            }
        }

        fun bind(category: Category, isSelected: Boolean) {
            tvTitle.text = category.title
            tvCount.text = if (category.count > 0) "(${category.count})" else ""
            itemView.isSelected = isSelected
        }
    }
}
