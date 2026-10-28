package model;

/** One account in the Chart of Accounts. Records are small, read-only data holders. */
public record Account(int id, String number, String name, Type type, Normal normal,
                      String description, boolean active) {

    public enum Normal { DEBIT, CREDIT }

    public enum Type {
        ASSET("Assets", Normal.DEBIT),
        LIABILITY("Liabilities", Normal.CREDIT),
        EQUITY("Owner's Equity", Normal.CREDIT),
        REVENUE("Revenue", Normal.CREDIT),
        EXPENSE("Expenses", Normal.DEBIT);

        private final String label;
        private final Normal natural;

        Type(String label, Normal natural) {
            this.label = label;
            this.natural = natural;
        }

        /** The side on which this kind of account normally increases. */
        public Normal natural() {
            return natural;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Shown in drop-down lists. */
    @Override
    public String toString() {
        return number + " \u2014 " + name;
    }
}
