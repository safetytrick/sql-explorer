package com.github.safetytrick.sqlexplorer.commentary;

import java.time.Instant;

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
@Table(name="notes")
@EntityListeners(AuditingEntityListener.class)
class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String path;

    /** Which environment this note belongs to. See {@link CommentaryEnvironment}. */
    private String environmentName;
    private String text;

    @ManyToOne
    private User author;

    @CreatedDate
    private Instant createdOn;

}
