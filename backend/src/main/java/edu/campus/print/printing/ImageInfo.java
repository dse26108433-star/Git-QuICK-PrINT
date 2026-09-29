package edu.campus.print.printing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What the server learned about a picture. The website draws its preview
 * from these numbers and the Xerox PC lays the picture out from them, so
 * both agree on the size of "Actual size".
 *
 * @param widthPx         as stored in the file
 * @param heightPx        as stored in the file
 * @param dpi             dots per inch written in the file, or 96 when it says nothing sensible
 * @param exifOrientation 1 (upright) .. 8, from a phone camera's EXIF note; the picture is shown turned upright
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImageInfo(int widthPx, int heightPx, double dpi, int exifOrientation) {

    public static final double DEFAULT_DPI = 96;

    /** Width as the picture should look (after the EXIF turn). */
    public int lookWidthPx() {
        return quarterTurn() ? heightPx : widthPx;
    }

    public int lookHeightPx() {
        return quarterTurn() ? widthPx : heightPx;
    }

    private boolean quarterTurn() {
        return exifOrientation >= 5 && exifOrientation <= 8;
    }
}
