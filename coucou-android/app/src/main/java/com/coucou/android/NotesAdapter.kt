package com.coucou.android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NotesAdapter(
    private var notes: List<TaskStore.NoteRecord>,
    private val onNoteSelected: (Long) -> Unit,
) : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

    fun updateNotes(newNotes: List<TaskStore.NoteRecord>) {
        notes = newNotes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_main_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(notes[position])
    }

    override fun getItemCount(): Int = notes.size

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val noteText: TextView = itemView.findViewById(R.id.note_text)

        fun bind(note: TaskStore.NoteRecord) {
            noteText.text = note.text
            itemView.setOnClickListener { onNoteSelected(note.id) }
        }
    }
}
