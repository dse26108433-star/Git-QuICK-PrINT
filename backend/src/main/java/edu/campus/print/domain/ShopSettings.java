package edu.campus.print.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** Name and prices. One row, edited on the counter screen. Prices in paise. */
@Entity
@Table(name = "shop_settings")
public class ShopSettings {

    @Id
    private Integer id = 1;

    @Column(name = "center_name", nullable = false) private String centerName;
    @Column(name = "price_bw_paise", nullable = false) private int priceBwPaise;
    @Column(name = "price_color_paise", nullable = false) private int priceColorPaise;
    @Column(nullable = false) private String currency = "INR";
    /** Print the pickup code small on each order's first page. Staff can switch it off for a while. */
    @Column(name = "stamp_code", nullable = false) private boolean stampCode = true;
    @Column(name = "updated_at") private Instant updatedAt;

    public int pricePerPage(boolean color) {
        return color ? priceColorPaise : priceBwPaise;
    }

    public String getCenterName() { return centerName; }
    public void setCenterName(String v) { this.centerName = v; }
    public int getPriceBwPaise() { return priceBwPaise; }
    public void setPriceBwPaise(int v) { this.priceBwPaise = v; }
    public int getPriceColorPaise() { return priceColorPaise; }
    public void setPriceColorPaise(int v) { this.priceColorPaise = v; }
    public String getCurrency() { return currency; }
    public boolean isStampCode() { return stampCode; }
    public void setStampCode(boolean v) { this.stampCode = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
