package com.github.safetytrick.sqlexplorer.commentary;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name="Tags")
@EnvironmentScoped
@RestController
class NotesResource {

    private final NoteRepository noteRepository;
    private final UserService userService;
    private final CommentaryEnvironment environment;

    public NotesResource(NoteRepository noteRepository, UserService userService,
                         CommentaryEnvironment environment) {
        this.noteRepository = noteRepository;
        this.userService = userService;
        this.environment = environment;
    }

    @GetMapping("/notes/path")
    public List<Note> notesForPath(@RequestParam String path) {
        return noteRepository.findAllByPathAndEnvironmentName(path, environment.name());
    }

    /**
     * The notes on each of these paths, keyed by exactly the paths sent.
     *
     * <p>For a list of rows — a table's columns, whose notes live at
     * {@code /schema/{s}/table/{t}/column/{c}} (sqlx-n5j.1) — so a page with a
     * hundred columns asks once rather than a hundred times. A path with no
     * notes is absent from the map.
     */
    @PostMapping("/notes/paths")
    public Map<String, List<Note>> notesForPaths(@RequestBody List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Map.of();
        }
        return noteRepository.findAllByPathInAndEnvironmentName(paths, environment.name()).stream()
            .collect(Collectors.groupingBy(Note::getPath));
    }

    @GetMapping("/note/{id}")
    public Note noteById(@PathVariable int id) {
        return noteRepository.findById(id).orElseThrow();
    }

    @PostMapping("/notes")
    public Note createNote(@RequestParam String text, @RequestParam String path) {
        Objects.requireNonNull(text, "text is required");
        Objects.requireNonNull(path, "path is required");

        Note note = new Note();
        note.setAuthor(userService.currentUser());
        note.setText(text);
        note.setPath(path);
        note.setEnvironmentName(environment.name());

        note = noteRepository.save(note);

        return note;
    }
}
