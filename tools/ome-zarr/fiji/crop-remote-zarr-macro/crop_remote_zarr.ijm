// crop_remote_zarr.ijm
// Fiji macro: open a low-resolution level of a remote (OME-)Zarr, let the user
// draw a box (XY rectangle + Z range), then fetch ONLY that box at full
// resolution and save it as a local TIFF.
//
// Uses Fiji's built-in N5 importer (File > Import > HDF5/N5/Zarr/OME-NGFF ...).
// Works with http(s):// and s3:// URLs that Fiji's N5 libraries can read.
// The two "level" fields are the array paths inside the multiscale group,
// e.g. "0" (full res) and "3" (8x downsampled) for a typical OME-Zarr.

#@ String  (label="OME-Zarr URL (multiscale group)", description="e.g. https://server/bucket/image.ome.zarr") url
#@ String  (label="Low-res level to pick region on", value="3") lowLevel
#@ String  (label="Level to export", value="0") highLevel
#@ Integer (label="XY downsampling between the two levels", value=8) fxy
#@ Integer (label="Z downsampling between the two levels", value=8) fz
#@ String  (label="Array axes (Zarr order)", choices={"zyx (3D)", "czyx (4D)", "tczyx (5D)"}) layout
#@ File    (label="Output TIFF", style="save") outFile

cmd = "HDF5/N5/Zarr/OME-NGFF ... ";
url = replace(url, "/+$", "");   // strip trailing slash

// ---- 1. open the low-res overview (small, loaded fully) -------------------
run(cmd, "url=[" + url + "?/" + lowLevel + "]");
getDimensions(w, h, nc, nz, nt);
getVoxelSize(pw, ph, pd, unit);
bytesPerPx = bitDepth() / 8;

// ---- 2. user picks the box -------------------------------------------------
setTool("rectangle");
waitForUser("Crop 1/3", "Draw a RECTANGLE around the region of interest,\nthen click OK.");
if (selectionType() != 0) exit("Please draw a rectangle selection.");
getSelectionBounds(rx, ry, rw, rh);

waitForUser("Crop 2/3", "Scroll to the FIRST z-slice of the region,\nthen click OK.");
Stack.getPosition(ch, zA, tA);
waitForUser("Crop 3/3", "Scroll to the LAST z-slice of the region,\nthen click OK.");
Stack.getPosition(ch, zB, tB);
zLo = minOf(zA, zB) - 1;   // 0-based
zHi = maxOf(zA, zB) - 1;

// ---- 3. convert to full-resolution voxel indices --------------------------
// (max values are clipped to a safe bound so we never read past the array edge;
//  this can trim up to factor-1 voxels at the far edge of the volume)
x0 = rx * fxy;  x1 = minOf((rx + rw) * fxy - 1, (w - 1) * fxy);
y0 = ry * fxy;  y1 = minOf((ry + rh) * fxy - 1, (h - 1) * fxy);
z0 = zLo * fz;  z1 = minOf((zHi + 1) * fz - 1, (nz - 1) * fz);

// N5 dimension order is the reverse of Zarr order: x,y,z[,c[,t]]
mn = "" + x0 + "," + y0 + "," + z0;
mx = "" + x1 + "," + y1 + "," + z1;
nCh = 1;
if (startsWith(layout, "czyx") || startsWith(layout, "tczyx")) {
	mn = mn + ",0";  mx = mx + "," + (nc - 1);  nCh = nc;     // all channels
}
if (startsWith(layout, "tczyx")) {
	mn = mn + "," + (tA - 1);  mx = mx + "," + (tA - 1);      // current timepoint
}

sx = x1 - x0 + 1;  sy = y1 - y0 + 1;  sz = z1 - z0 + 1;
mb = sx * sy * sz * nCh * bytesPerPx / (1024 * 1024);
print("Export box (level " + highLevel + " voxels)  min=" + mn + "  max=" + mx);
print("Size: " + sx + " x " + sy + " x " + sz + " x " + nCh + " ch  (~" + d2s(mb, 0) + " MB)");
if (!getBoolean("About to download ~" + d2s(mb, 0) + " MB (" + sx + " x " + sy + " x " + sz + ").\nContinue?"))
	exit();

// ---- 4. fetch just that box at full resolution ----------------------------
lowTitle = getTitle();
run(cmd, "url=[" + url + "?/" + highLevel + "] min=" + mn + " max=" + mx);

// if the importer found no calibration, derive it from the overview
getVoxelSize(cw, chh, cd, cunit);
if (cunit == "pixel" || cunit == "pixels" || cunit == "")
	setVoxelSize(pw / fxy, ph / fxy, pd / fz, unit);
setMetadata("Info", "Source: " + url + "\nLevel: " + highLevel + "\nmin=" + mn + "\nmax=" + mx);

// ---- 5. save ---------------------------------------------------------------
saveAs("Tiff", outFile);
print("Saved: " + outFile);
close(lowTitle);
