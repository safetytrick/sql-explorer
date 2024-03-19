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
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@Table(name="notes")
@EntityListeners(AuditingEntityListener.class)
class Note {

    @Id
    @GeneratedValue(strategy= GenerationType.AUTO, generator="native")
    @GenericGenerator(name = "native", strategy = "native")
    private Integer id;

    private String path;
    private String text;

    @ManyToOne
    private User author;

    @CreatedDate
    private Instant createdOn;

}
