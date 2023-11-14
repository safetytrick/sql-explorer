package com.github.safetytrick.sqlexplorer.anno;

import java.util.List;

public interface TagLoader {
    List<Tag> findAllByPath(String path);
}
