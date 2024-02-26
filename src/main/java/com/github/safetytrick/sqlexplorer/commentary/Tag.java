package com.github.safetytrick.sqlexplorer.commentary;

import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.auth.User;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@Table(name="tags")
@EntityListeners(AuditingEntityListener.class)
public class Tag {
    private static final Pattern PATH_ELEMENTS = Pattern.compile("/(?<key>\\w+)/(?<value>\\w+)");

    @Id
    @GeneratedValue(strategy= GenerationType.AUTO, generator="native")
    @GenericGenerator(name = "native", strategy = "native")
    private Integer id;

    private String name;
    private String path;
    private String type;

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
