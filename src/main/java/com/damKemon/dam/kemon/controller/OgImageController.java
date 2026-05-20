package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.service.ProductService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * 1200×630 product PNG for OG / Twitter card previews. Server-renders
 * with AWT — no external deps, no headless browser, no fonts to install.
 * Cached aggressively (1 day immutable) since the image only changes when
 * the price does.
 *
 * <p>WhatsApp, Facebook, Slack, Twitter all crawl this once and serve the
 * cached preview thereafter.
 */
@RestController
@RequestMapping("/api/og")
public class OgImageController {

    private static final int W = 1200, H = 630;

    private final ProductService productService;

    public OgImageController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping(value = "/product/{idOrSlug}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> productImage(@PathVariable String idOrSlug) {
        Optional<Product> p = productService.findByIdOrSlug(idOrSlug);
        if (p.isEmpty()) return ResponseEntity.notFound().build();

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Cream background
        g.setColor(new Color(0xFB, 0xF7, 0xED));
        g.fillRect(0, 0, W, H);

        // Top-left brand
        g.setColor(new Color(0x1D, 0x1D, 0x1B));
        g.setFont(new Font("Serif", Font.BOLD, 36));
        g.drawString("dam", 64, 90);
        g.setColor(new Color(0xFF, 0x45, 0x21));
        g.drawString(".", 64 + g.getFontMetrics().stringWidth("dam"), 90);
        g.setColor(new Color(0x1D, 0x1D, 0x1B));
        g.drawString("kemon", 64 + g.getFontMetrics().stringWidth("dam") + g.getFontMetrics().stringWidth("."), 90);

        // Product name — auto-fit by line wrapping
        Product product = p.get();
        String name = product.getName() == null ? "Product" : product.getName();
        g.setFont(new Font("Serif", Font.BOLD, 56));
        drawWrappedText(g, name, 64, 200, W - 128, 70, 4);

        // Price
        if (product.getLowestPrice() != null) {
            g.setColor(new Color(0xFF, 0x45, 0x21));
            g.setFont(new Font("SansSerif", Font.BOLD, 84));
            String price = "৳" + (long) (double) product.getLowestPrice();
            g.drawString(price, 64, H - 110);
        }

        // Seller count badge
        int sellerCount = product.getPrices() == null ? 0 : product.getPrices().size();
        if (sellerCount > 1) {
            String badge = sellerCount + " sellers compared";
            g.setFont(new Font("SansSerif", Font.PLAIN, 28));
            FontMetrics fm = g.getFontMetrics();
            int bw = fm.stringWidth(badge) + 32;
            int bx = W - 64 - bw;
            int by = H - 145;
            g.setColor(new Color(0x0F, 0x4D, 0x2A));
            g.fillRoundRect(bx, by, bw, 60, 30, 30);
            g.setColor(Color.WHITE);
            g.drawString(badge, bx + 16, by + 40);
        }

        // Category chip
        if (product.getCategory() != null) {
            g.setColor(new Color(0x1D, 0x1D, 0x1B, 0x99));
            g.setFont(new Font("Monospaced", Font.PLAIN, 22));
            g.drawString(product.getCategory().toUpperCase(), 64, 140);
        }

        // Footer
        g.setColor(new Color(0x1D, 0x1D, 0x1B, 0x88));
        g.setFont(new Font("Monospaced", Font.PLAIN, 22));
        g.drawString("Bangladesh price comparison · one search across 60+ shops", 64, H - 40);

        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setCacheControl("public, max-age=86400, immutable");
            return new ResponseEntity<>(baos.toByteArray(), headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private static void drawWrappedText(Graphics2D g, String text, int x, int y,
                                        int maxWidth, int lineHeight, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        int drawn = 0;
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth) {
                if (line.length() == 0) {
                    // single word wider than maxWidth — print it truncated
                    g.drawString(word, x, y + drawn * lineHeight);
                    drawn++;
                } else {
                    g.drawString(line.toString(), x, y + drawn * lineHeight);
                    drawn++;
                    line.setLength(0);
                    line.append(word);
                }
                if (drawn >= maxLines) return;
            } else {
                if (line.length() == 0) line.append(word);
                else line.append(' ').append(word);
            }
        }
        if (line.length() > 0 && drawn < maxLines) {
            g.drawString(line.toString(), x, y + drawn * lineHeight);
        }
    }
}
