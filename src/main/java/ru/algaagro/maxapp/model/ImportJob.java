package ru.algaagro.maxapp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "import_jobs")
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long initiatedByMaxUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportStatus status = ImportStatus.PENDING;

    @Lob
    private String sourceFilesJson = "[]";

    @Lob
    private String summary = "";

    @Lob
    private String previewJson = "[]";

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @PrePersist
    public void onCreate() {
        createdAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        if ((status == ImportStatus.COMPLETED || status == ImportStatus.FAILED || status == ImportStatus.CANCELLED) && completedAt == null) {
            completedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getInitiatedByMaxUserId() {
        return initiatedByMaxUserId;
    }

    public void setInitiatedByMaxUserId(Long initiatedByMaxUserId) {
        this.initiatedByMaxUserId = initiatedByMaxUserId;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public void setStatus(ImportStatus status) {
        this.status = status;
    }

    public String getSourceFilesJson() {
        return sourceFilesJson;
    }

    public void setSourceFilesJson(String sourceFilesJson) {
        this.sourceFilesJson = sourceFilesJson;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getPreviewJson() {
        return previewJson;
    }

    public void setPreviewJson(String previewJson) {
        this.previewJson = previewJson;
    }
}
