package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;
import java.util.Objects;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotesResource {

    private final NoteRepository noteRepository;
    private final UserService userService;

    public NotesResource(NoteRepository noteRepository, UserService userService) {
        this.noteRepository = noteRepository;
        this.userService = userService;
    }

    @GetMapping("/api/sql-explorer/notes/path")
    public List<Note> notesForPath(@RequestParam(name="path") String path) {
        return noteRepository.findAllByPath(path);
    }

    @GetMapping("/api/sql-explorer/note/{id}")
    public Note noteById(@PathVariable(name="id") int id) {
        return noteRepository.findById(id).orElseThrow();
    }

    @PostMapping("/api/sql-explorer/notes")
    public Note createNote(@RequestParam("text") String text, @RequestParam("path") String path) {
        Objects.requireNonNull(text, "text is required");
        Objects.requireNonNull(path, "path is required");

        Note note = new Note();
        note.setAuthor(userService.currentUser());
        note.setText(text);
        note.setPath(path);

        note = noteRepository.save(note);

        return note;
    }
}
