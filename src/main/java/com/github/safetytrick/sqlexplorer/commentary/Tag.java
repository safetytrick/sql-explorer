package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.auth.User;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@Table(name="tags")
@EntityListeners(AuditingEntityListener.class)
public class Tag {
    private static final Pattern PATH_ELEMENTS = Pattern.compile("/(?<key>[^/]+)/(?<value>[^/]+)");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;
    private String path;
    private String type;

    /**
     * Which environment this association belongs to.
     *
     * <p>Not nullable, and there is deliberately no "everywhere" value: a tag
     * is about an object in one corpus. See {@link CommentaryEnvironment}.
     */
    private String environmentName;

    @ManyToOne
    private User author;

    @CreatedDate
    private Instant createdOn;

    public Map<String,String> getPathElements() {
        Map<String,String> result = new HashMap<>();
        Matcher matcher = PATH_ELEMENTS.matcher(this.path);
        while (matcher.find()) {
            result.put(matcher.group("key"), matcher.group("value"));
        }
        return result;
    }

}
