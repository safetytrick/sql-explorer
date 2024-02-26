package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

interface NoteRepository extends CrudRepository<Note,Integer> {
    List<Note> findAllByPath(String path);
    List<Note> findAllByPathIn(List<String> paths);
}
