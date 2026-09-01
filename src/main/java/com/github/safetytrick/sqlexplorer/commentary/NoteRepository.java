package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

interface NoteRepository extends CrudRepository<Note,Integer> {
    List<Note> findAllByPathAndEnvironmentName(String path, String environmentName);
    List<Note> findAllByPathInAndEnvironmentName(List<String> paths, String environmentName);
}
