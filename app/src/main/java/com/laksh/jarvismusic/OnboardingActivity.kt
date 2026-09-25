package com.laksh.jarvismusic

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView

data class SelectionItem(val name: String, val imageUrl: String, var isSelected: Boolean = false)

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val MIN_ARTISTS = 3
    }

    private lateinit var rv: RecyclerView
    private lateinit var btnNext: MaterialButton

    private val selectedItems = mutableListOf<String>()

    private val artists = ArtistCatalog.popular.map { SelectionItem(it.name, it.imageUrl) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_onboarding)

        val root = findViewById<View>(R.id.onboarding_root)
        root.applyStatusBarPadding()
        btnNext = findViewById(R.id.btn_next_onboarding)
        btnNext.applyNavigationBarMargin()

        rv = findViewById(R.id.rv_onboarding)
        rv.layoutManager = GridLayoutManager(this, 3)
        setupAdapter(artists)
        updateButton()

        btnNext.setOnClickListener {
            if (selectedItems.size < MIN_ARTISTS) {
                Toast.makeText(this, "Pick at least $MIN_ARTISTS artists", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            LocalStore.completeOnboarding(this, selectedItems.toList())
            MusicState.notifyLibraryChanged()
            finish()
        }
    }

    private fun updateButton() {
        btnNext.alpha = if (selectedItems.size >= MIN_ARTISTS) 1f else 0.5f
    }

    private fun setupAdapter(list: List<SelectionItem>) {
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val holder = object : RecyclerView.ViewHolder(
                    LayoutInflater.from(p.context).inflate(R.layout.item_onboarding, p, false)
                ) {}
                holder.itemView.setOnClickListener {
                    // Use the holder's current position, not the one captured at bind time
                    val position = holder.bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                    val item = list[position]
                    item.isSelected = !item.isSelected
                    if (item.isSelected) selectedItems.add(item.name) else selectedItems.remove(item.name)
                    updateButton()
                    notifyItemChanged(position)
                }
                return holder
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val item = list[i]
                val name = h.itemView.findViewById<TextView>(R.id.tv_selection_name)
                val img = h.itemView.findViewById<ShapeableImageView>(R.id.img_selection)

                name.text = item.name

                // Header needed for the encrypted gstatic CDN links
                val glideUrl = GlideUrl(
                    item.imageUrl,
                    LazyHeaders.Builder().addHeader("User-Agent", "Mozilla/5.0").build()
                )

                Glide.with(this@OnboardingActivity)
                    .load(glideUrl)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .placeholder(R.drawable.placeholder_art)
                    .error(R.drawable.placeholder_art)
                    .into(img)

                // Selection state
                if (item.isSelected) {
                    img.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics)
                    h.itemView.scaleX = 1.05f
                    h.itemView.scaleY = 1.05f
                    h.itemView.alpha = 1.0f
                } else {
                    img.strokeWidth = 0f
                    h.itemView.scaleX = 1.0f
                    h.itemView.scaleY = 1.0f
                    h.itemView.alpha = 0.85f
                }
            }

            override fun getItemCount() = list.size
        }
    }
}
