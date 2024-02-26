package com.github.safetytrick.sqlexplorer.commentary;

import java.util.List;

public interface TagLoader {
    List<Tag> findAllByPath(String path);
}
