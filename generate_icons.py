import os
from PIL import Image

src_img_path = r'C:\Users\USER X\Downloads\audio app\app logo\SonicSort.png'
base_res_dir = r'C:\Users\USER X\Downloads\audio app\app\src\main\res'

sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

try:
    img = Image.open(src_img_path)
except Exception as e:
    print(f"Error loading image: {e}")
    exit(1)

# Generate traditional and round mipmaps
for density, size in sizes.items():
    folder = os.path.join(base_res_dir, f'mipmap-{density}')
    os.makedirs(folder, exist_ok=True)
    
    # Remove old webp files
    for old_file in ['ic_launcher.webp', 'ic_launcher_round.webp']:
        old_path = os.path.join(folder, old_file)
        if os.path.exists(old_path):
            os.remove(old_path)
            
    # Resize and save
    resized = img.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(os.path.join(folder, 'ic_launcher.png'), format='PNG')
    resized.save(os.path.join(folder, 'ic_launcher_round.png'), format='PNG')

# Generate foreground for adaptive icons
drawable_folder = os.path.join(base_res_dir, 'drawable')
old_fg = os.path.join(drawable_folder, 'ic_launcher_foreground.xml')
if os.path.exists(old_fg):
    os.remove(old_fg)

fg_resized = img.resize((108, 108), Image.Resampling.LANCZOS)
fg_resized.save(os.path.join(drawable_folder, 'ic_launcher_foreground.png'), format='PNG')

print("Icon generation complete.")
