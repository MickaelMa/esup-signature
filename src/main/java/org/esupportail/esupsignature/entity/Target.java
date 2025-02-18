package org.esupportail.esupsignature.entity;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Entity
public class Target {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private String targetUri;

    private Boolean targetOk = false;

    public Boolean getTargetOk() {
        return targetOk;
    }

    public void setTargetOk(Boolean targetOk) {
        this.targetOk = targetOk;
    }

    public String getTargetUri() {
        return targetUri;
    }

    public void setTargetUri(String targetUri) {
        this.targetUri = targetUri;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getProtectedTargetUri() {
        if(targetUri != null) {
            Pattern p = Pattern.compile("[^@]*:\\/\\/[^:]*:([^@]*)@.*?$");
            Matcher m = p.matcher(targetUri);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, m.group(0).replaceFirst(Pattern.quote(m.group(1)), "********"));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        return "";
    }
}
