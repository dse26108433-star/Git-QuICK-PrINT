package edu.campus.agent.print;

import edu.campus.agent.net.Messages.ClaimedJob;
import edu.campus.agent.net.Messages.ImageInfo;
import edu.campus.agent.net.Messages.JobSettings;
import edu.campus.agent.net.Messages.Paper;

/**
 * Everything needed to print one document on one printer: the file's kind,
 * the student's settings (exactly as shown to them before paying), the paper
 * and, for pictures, what the server measured.
 *
 * @param pickupLabel false = the counter switched the label off: print only the student's file
 */
public record PrintJob(
        String jobId,
        String windowsPrinterName,
        String fileType,        // PDF, PNG, JPEG
        String pickupCode,
        String fileName,
        int documentNumber,
        int documentCount,
        JobSettings settings,
        Paper paper,
        ImageInfo image,
        boolean pickupLabel
) {
    public static PrintJob of(ClaimedJob j) {
        JobSettings s = j.settings() == null ? JobSettings.plain(1, false, null) : j.settings();
        Paper paper = j.paper() == null ? new Paper("A4", 210, 297) : j.paper();
        return new PrintJob(j.jobId(), j.windowsPrinterName(), j.fileType(), j.pickupCode(), j.fileName(),
                Math.max(1, j.documentNumber()), Math.max(1, j.documentCount()), s, paper, j.image(),
                j.stampCode() == null || j.stampCode());
    }

    /** Name shown in the Windows print queue; the spooler monitor looks for it. */
    public String jobName() {
        String shortId = jobId.length() > 8 ? jobId.substring(0, 8) : jobId;
        return "CampusPrint-" + pickupCode + "-" + shortId;
    }

    public boolean isImage() {
        return "PNG".equals(fileType) || "JPEG".equals(fileType);
    }

    public int copies() {
        return Math.max(1, settings.copies());
    }

    public boolean color() {
        return settings.color();
    }

    public boolean allPages() {
        return settings.pages() == null || settings.pages().isBlank();
    }
}
