#@ String  (label="OME-Zarr URL (multiscale group)", description="e.g. https://server/bucket/image.ome.zarr") url
#@ String  (label="Low-res level to pick region on", value="3") lowLevel
#@ String  (label="Level to export", value="0") highLevel
#@ Integer (label="XY downsampling between the two levels", value=8, min=1) fxy
#@ Integer (label="Z downsampling between the two levels", value=8, min=1) fz
#@ String  (label="Array axes (Zarr order)", choices={"zyx (3D)", "czyx (4D)", "tczyx (5D)"}) layout
#@ File    (label="Output TIFF", style="save") outFile
#@ Boolean (label="Require physical calibration", value=true, description="Stop if the image has no voxel size, rather than saving in pixels") requireCal

// Crop a region from a remote OME-Zarr and save it as a local TIFF.
//
// Opens a low-resolution level in full, lets you draw a box on it (an XY
// rectangle and a z range), then fetches only that box at the chosen level
// with Fiji's N5 importer (File > Import > HDF5/N5/Zarr/OME-NGFF ...).
//
// Input:  the URL of an OME-Zarr multiscale group (http(s):// or s3://), and
//         the array paths of two levels in it, e.g. "3" (8x downsampled) to
//         choose the region on and "0" (full resolution) to export.
// Output: the crop as a TIFF, and <name>.provenance.json beside it, recording
//         the parameters, crop box, voxel size, ImageJ version and date.
//
// It needs a screen to draw the box, so unlike most Fiji scripts it can't run
// headless. It's an ImageJ macro, not Groovy, so that it is easy to read and
// adapt during the workshop.

cmd = "HDF5/N5/Zarr/OME-NGFF ... ";
url = replace(url, "/+$", "");   // strip trailing slash

// ---- 1. open the low-res overview (small, loaded fully) -------------------
run(cmd, "url=[" + url + "?/" + lowLevel + "]");
getDimensions(w, h, nc, nz, nt);
getVoxelSize(pw, ph, pd, unit);
bytesPerPx = bitDepth() / 8;
// A crop saved in pixels silently loses its link to physical space, so stop
// unless the user has said that's acceptable.
if (unit == "pixel" || unit == "pixels" || unit == "") {
	if (requireCal)
		exit("This OME-Zarr has no voxel size, so the crop would be in pixels.\nUntick 'Require physical calibration' to save it anyway.");
	print("Warning: no voxel size found; the crop is calibrated in pixels.");
}

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

// If the importer found no calibration, derive it from the overview.
getVoxelSize(cw, chh, cd, cunit);
if ((cunit == "pixel" || cunit == "pixels" || cunit == "") && unit != "pixel" && unit != "pixels" && unit != "") {
	setVoxelSize(pw / fxy, ph / fxy, pd / fz, unit);
	getVoxelSize(cw, chh, cd, cunit);
}
setMetadata("Info", "Source: " + url + "\nLevel: " + highLevel + "\nmin=" + mn + "\nmax=" + mx);

// ---- 5. save ---------------------------------------------------------------
saveAs("Tiff", outFile);

provFile = replace(outFile, "\\.tiff?$", "") + ".provenance.json";
q = "\"";
json = "{\n";
json = json + "  " + q + "script" + q + ": " + q + "crop_remote_zarr.ijm" + q + ",\n";
json = json + "  " + q + "created" + q + ": " + q + timestamp() + q + ",\n";
json = json + "  " + q + "imagej" + q + ": " + q + getVersion() + q + ",\n";
json = json + "  " + q + "parameters" + q + ": {" + q + "url" + q + ": " + q + jsonEscape(url) + q
	+ ", " + q + "lowLevel" + q + ": " + q + jsonEscape(lowLevel) + q + ", " + q + "highLevel" + q + ": " + q + jsonEscape(highLevel) + q
	+ ", " + q + "fxy" + q + ": " + fxy + ", " + q + "fz" + q + ": " + fz
	+ ", " + q + "layout" + q + ": " + q + jsonEscape(layout) + q + ", " + q + "requireCalibration" + q + ": " + jsonBool(requireCal) + "},\n";
// Inclusive voxel indices at the exported level, in N5 order (x,y,z[,c[,t]]).
json = json + "  " + q + "box" + q + ": {" + q + "min" + q + ": [" + mn + "], " + q + "max" + q + ": [" + mx + "]},\n";
json = json + "  " + q + "voxelSize" + q + ": {" + q + "x" + q + ": " + cw + ", " + q + "y" + q + ": " + chh
	+ ", " + q + "z" + q + ": " + cd + ", " + q + "unit" + q + ": " + q + jsonEscape(cunit) + q + "},\n";
json = json + "  " + q + "outputs" + q + ": [" + q + jsonEscape(outFile) + q + "]\n}\n";
File.saveString(json, provFile);

print("Saved: " + outFile + " and " + File.getName(provFile));
close(lowTitle);

// Windows paths contain backslashes, which JSON requires to be escaped.
function jsonEscape(text) {
	text = replace(text, "\\\\", "\\\\\\\\");
	return replace(text, "[\"]", "\\\\\"");
}

function jsonBool(b) {
	if (b) return "true";
	return "false";
}

// ISO 8601 local date and time, e.g. 2026-11-30T09:15:00
function timestamp() {
	getDateAndTime(year, month, dayOfWeek, dayOfMonth, hour, minute, second, msec);
	return "" + year + "-" + IJ.pad(month + 1, 2) + "-" + IJ.pad(dayOfMonth, 2)
		+ "T" + IJ.pad(hour, 2) + ":" + IJ.pad(minute, 2) + ":" + IJ.pad(second, 2);
}
