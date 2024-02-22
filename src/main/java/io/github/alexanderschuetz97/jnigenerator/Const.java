package io.github.alexanderschuetz97.jnigenerator;

public class Const {

    private String classname;
    private String headerFile;
    private String codeFile;
    private String[] headers;

    private String[] constFilters;

    public String[] getConstFilters() {
        return constFilters;
    }

    public void setConstFilters(String[] constFilters) {
        this.constFilters = constFilters;
    }

    public String getClassname() {
        return classname;
    }

    public void setClassname(String classname) {
        this.classname = classname;
    }

    public String[] getHeaders() {
        return headers;
    }

    public void setHeaders(String[] headers) {
        this.headers = headers;
    }

    public String getHeaderFile() {
        return headerFile;
    }

    public void setHeaderFile(String headerFile) {
        this.headerFile = headerFile;
    }

    public String getCodeFile() {
        return codeFile;
    }

    public void setCodeFile(String codeFile) {
        this.codeFile = codeFile;
    }
}
