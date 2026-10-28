package model;

import java.util.List;

public record Statement(String title, String subtitle, List<StatementLine> lines, List<String> warnings) {
}
