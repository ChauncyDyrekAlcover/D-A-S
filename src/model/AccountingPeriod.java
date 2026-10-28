package model;

import java.time.LocalDate;

public record AccountingPeriod(int id, String name, LocalDate start, LocalDate end,
                               boolean closed, String closedAt) {

    public boolean contains(LocalDate d) {
        return !d.isBefore(start) && !d.isAfter(end);
    }

    @Override
    public String toString() {
        return name + " (" + start + " to " + end + ")" + (closed ? " [CLOSED]" : "");
    }
}
