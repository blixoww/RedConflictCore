package fr.redconflict.hdv;

import org.bukkit.inventory.ItemStack;

public class HdvListing {
    private int id;

    private String sellerUuid;

    private String sellerName;

    private ItemStack item;

    private long totalPrice;

    private int quantity;

    private long expiresAt;

    private boolean sold;

    /** true = l'acheteur paie en PB, false = monnaie ($). */
    private boolean payPB;

    /** Prix en PB pour annonce double-devise (>0 => dual). 0 sinon. */
    private long pricePB;

    public HdvListing() {}

    public HdvListing(int id, String sellerUuid, String sellerName, ItemStack item, long totalPrice, int quantity, long expiresAt) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.item = item;
        this.totalPrice = totalPrice;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
        this.sold = false;
        this.payPB = false;
    }

    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getSellerUuid() {
        return this.sellerUuid;
    }

    public void setSellerUuid(String u) {
        this.sellerUuid = u;
    }

    public String getSellerName() {
        return this.sellerName;
    }

    public void setSellerName(String n) {
        this.sellerName = n;
    }

    public ItemStack getItem() {
        return this.item;
    }

    public void setItem(ItemStack i) {
        this.item = i;
    }

    public long getTotalPrice() {
        return this.totalPrice;
    }

    public void setTotalPrice(long p) {
        this.totalPrice = p;
    }

    public int getQuantity() {
        return this.quantity;
    }

    public void setQuantity(int q) {
        this.quantity = q;
    }

    public long getExpiresAt() {
        return this.expiresAt;
    }

    public void setExpiresAt(long e) {
        this.expiresAt = e;
    }

    public boolean isSold() {
        return this.sold;
    }

    public void setSold(boolean s) {
        this.sold = s;
    }

    public boolean isPayPB() {
        return this.payPB;
    }

    public void setPayPB(boolean p) {
        this.payPB = p;
    }

    public long getPricePB() { return this.pricePB; }
    public void setPricePB(long p) { this.pricePB = p; }

    /** Vrai si l'annonce propose les deux devises (acheteur au choix). */
    public boolean isDual() { return !this.payPB && this.pricePB > 0 && this.totalPrice > 0; }

    public boolean isExpired() {
        return (this.expiresAt > 0L && this.expiresAt < System.currentTimeMillis() / 1000L);
    }

    public long getPricePerUnit() {
        return (this.quantity > 0) ? (this.totalPrice / this.quantity) : 0L;
    }

    public String toString() {
        return "HdvListing{id=" + this.id + ", seller='" + this.sellerName + '\'' + ", total=" + this.totalPrice + ", qty=" + this.quantity + '}';
    }
}
