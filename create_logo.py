#!/usr/bin/env python3
"""Generate Cash-In logo with gradient and dollar sign."""

from PIL import Image, ImageDraw, ImageFont
import os

# Create a new image with transparent background
width, height = 400, 400
image = Image.new('RGBA', (width, height), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)

# Create gradient background (purple to orange)
# We'll draw a radial gradient by filling with circles
center_x, center_y = width // 2, height // 2
radius = width // 2

# Create gradient effect
for i in range(radius, 0, -1):
    # Calculate color: interpolate from purple (128, 0, 128) to orange (255, 165, 0)
    progress = (radius - i) / radius
    r = int(128 + (255 - 128) * progress)
    g = int(0 + (165 - 0) * progress)
    b = int(128 + (0 - 128) * progress)
    
    draw.ellipse(
        [center_x - i, center_y - i, center_x + i, center_y + i],
        fill=(r, g, b, 255)
    )

# Add glow effect (semi-transparent outer ring)
glow_color = (100, 50, 150, 100)  # Purple with transparency
draw.ellipse([10, 10, width - 10, height - 10], outline=glow_color, width=3)

# Draw dollar sign in the center
# Use a large font size
try:
    # Try to use a system font
    font_size = 200
    font = ImageFont.truetype("C:\\Windows\\Fonts\\arial.ttf", font_size)
except:
    # Fallback to default font
    font = ImageFont.load_default()

# Draw dollar sign
dollar_text = "$"
bbox = draw.textbbox((0, 0), dollar_text, font=font)
text_width = bbox[2] - bbox[0]
text_height = bbox[3] - bbox[1]

# Center the text
x = (width - text_width) // 2
y = (height - text_height) // 2 - 20

# Draw white dollar sign with slight shadow
draw.text((x + 3, y + 3), dollar_text, fill=(0, 0, 0, 100), font=font)  # Shadow
draw.text((x, y), dollar_text, fill=(255, 255, 255, 255), font=font)    # Main text

# Save the image
output_path = os.path.join(
    os.path.dirname(__file__),
    "server/static/logo.png"
)
image.save(output_path, 'PNG')
print(f"Logo created: {output_path}")
print(f"Size: {width}x{height} pixels")
