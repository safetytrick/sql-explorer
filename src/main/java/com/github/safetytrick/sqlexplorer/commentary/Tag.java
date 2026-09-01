package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Instant;
import java.util.Map;

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

    /**
     * The path's named parts — {@code schema}, {@code table} or {@code query}.
     *
     * <p>Empty when the stored path is not one of the three shapes, rather than
     * a best guess at its segments. A guess is what produced the defect this
     * replaced: a table named {@code a/b} yielded a type of {@code a} and a
     * table of {@code a}, and the tag became unreachable (sqlx-0lj).
     */
    public Map<String,String> getPathElements() {
        TagPath parsed = TagPath.parse(this.path);
        return parsed == null ? Map.of() : parsed.elements();
    }

}
