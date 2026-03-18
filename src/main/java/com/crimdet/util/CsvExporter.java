package com.crimdet.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public final class CsvExporter {

    private CsvExporter() {}

    /**
     * Export rows to a CSV file. First row is treated as header.
     */
    public static void exportDetectionLogs(List<String[]> rows, File file) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (String[] row : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(escapeCsv(row[i]));
                }
                writer.write(sb.toString());
                writer.newLine();
            }
        }
    }

    private static String escapeCsv(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
