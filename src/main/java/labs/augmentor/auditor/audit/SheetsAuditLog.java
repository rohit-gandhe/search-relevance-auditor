package labs.augmentor.auditor.audit;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.AddSheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.RunLog;
import labs.augmentor.auditor.model.AuditRecord;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One row per decision in a Google Sheet.
 *
 * Written with RAW, never USER_ENTERED. USER_ENTERED asks Sheets to interpret each
 * value the way the web UI would, and a Slack message ts — 1789072472.500979 —
 * parses as a number, loses its trailing digits, and stops matching the message it
 * refers to. The one column whose entire job is to find something again is the one
 * the convenient option quietly corrupts.
 */
public final class SheetsAuditLog implements AuditLog {

    private static final String APPLICATION = "relevance-auditor";

    private final Sheets sheets;
    private final String sheetId;
    private final String tab;

    public SheetsAuditLog() {
        this(Config.require("SHEET_ID"), Config.require("GOOGLE_SA_KEY"),
                Config.get("SHEET_TAB", "decisions"));
    }

    public SheetsAuditLog(String sheetId, String keyFile, String tab) {
        this.sheetId = sheetId;
        this.tab = tab;
        try {
            GoogleCredentials credentials;
            try (FileInputStream in = new FileInputStream(keyFile)) {
                credentials = GoogleCredentials.fromStream(in)
                        .createScoped(List.of(SheetsScopes.SPREADSHEETS));
            }
            this.sheets = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName(APPLICATION)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "could not open the sheet — is it shared with the service account's client_email?", e);
        }
    }

    /**
     * Make sure this run has its own tab, with its own headers.
     *
     * Appending to whatever tab happens to be first is how a row ends up written
     * under someone else's column names: the spreadsheet already held rows from an
     * earlier schema, the header check saw a non-empty first row and skipped, and
     * every value landed one column out — decided_at under "kind", the rule under
     * "attribute". Nothing failed. The row was simply wrong, in a sheet whose whole
     * purpose is being right.
     */
    public void ensureHeaders() {
        try {
            boolean exists = sheets.spreadsheets().get(sheetId).execute().getSheets().stream()
                    .anyMatch(s -> tab.equals(s.getProperties().getTitle()));
            if (!exists) {
                sheets.spreadsheets().batchUpdate(sheetId, new BatchUpdateSpreadsheetRequest()
                        .setRequests(List.of(new Request().setAddSheet(new AddSheetRequest()
                                .setProperties(new SheetProperties().setTitle(tab))))))
                        .execute();
            }
            ValueRange existing = sheets.spreadsheets().values().get(sheetId, range("A1:Q1")).execute();
            if (existing.getValues() == null || existing.getValues().isEmpty()) {
                append(List.of(new ArrayList<Object>(AuditRecord.HEADERS)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not prepare the sheet tab: " + e.getMessage(), e);
        }
    }

    private String range(String a1) {
        return "'" + tab + "'!" + a1;
    }

    @Override
    public void append(AuditRecord record) {
        append(List.of(record.toRow()));
        RunLog.record("recorded", "row appended for " + record.from() + " → " + record.to(),
                Map.of("sheet", sheetId, "decision", record.decisionId()));
    }

    private void append(List<List<Object>> rows) {
        try {
            sheets.spreadsheets().values()
                    .append(sheetId, range("A1"), new ValueRange().setValues(rows))
                    .setValueInputOption("RAW")          // never USER_ENTERED, see above
                    .setInsertDataOption("INSERT_ROWS")
                    .execute();
        } catch (Exception e) {
            throw new IllegalStateException("sheets append failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<AuditRecord> all() {
        // The sheet is the human-facing record, not a datastore to read back from.
        throw new UnsupportedOperationException("the sheet is written, not read");
    }

    /** Read the rows back as text, to show the sheet without leaving the terminal. */
    public List<List<Object>> rows() {
        try {
            List<List<Object>> values = sheets.spreadsheets().values()
                    .get(sheetId, range("A1:Q50")).execute().getValues();
            return values == null ? List.of() : values;
        } catch (Exception e) {
            throw new IllegalStateException("could not read the sheet: " + e.getMessage(), e);
        }
    }

    /** doctor: prove the credential and the share both work, without writing a row. */
    public String title() {
        try {
            return sheets.spreadsheets().get(sheetId).execute().getProperties().getTitle();
        } catch (Exception e) {
            throw new IllegalStateException("could not read the sheet: " + e.getMessage(), e);
        }
    }

    public static boolean keyFileExists() {
        return Files.exists(Path.of(Config.get("GOOGLE_SA_KEY", "./secrets/none.json")));
    }

    public static AuditRecord stub(String note) {
        return new AuditRecord(Instant.now(), "doctor", "doctor", note, "-", "-", "DOCTOR",
                "-", 0, 0, 0, 0, 0, 0, "", "", note);
    }
}
