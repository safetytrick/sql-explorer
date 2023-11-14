package com.github.safetytrick.sqlexplorer.anno;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

public interface NoteRepository extends CrudRepository<Note,Integer> {
    List<Note> findAllByPath(String path);
    List<Note> findAllByPathIn(List<String> paths);
}
