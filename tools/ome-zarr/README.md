# OME-Zarr tools

[OME-Zarr](https://ngff.openmicroscopy.org/) (OME-NGFF) stores large images as chunked, multi-resolution pyramids that can be read directly over the web. This means you can look at a large volume EM dataset and download only the part you need, rather than the whole file.

## Tools

| Tool | Language | What it does | Status |
|------|----------|--------------|--------|
| [Crop remote Zarr (macro)](./fiji/crop-remote-zarr-macro/README.md) | ImageJ macro | Pick a box on a low-resolution overview, then save that region at full resolution as a TIFF | Untested |
| [Crop remote Zarr (BigDataViewer)](./fiji/crop-remote-zarr-bdv/README.md) | Groovy | Browse the full pyramid in BigDataViewer, draw a 3D box, then save that region at full resolution as a TIFF | Untested |

## Which one should I use?

- **Macro**: the simplest option. It uses only standard Fiji menus and is easy to read and modify. You choose the region on a coarse overview, and you need to type in a few values, such as the resolution levels and the downsampling factor between them.
- **BigDataViewer**: a better way to choose the region. You can zoom to full resolution and move freely in 3D. It reads the scales and units from the OME-Zarr metadata. It currently exports a single channel and timepoint.

## Requirements

- An up-to-date [Fiji](https://fiji.sc/). The N5/Zarr readers and BigDataViewer come with Fiji, so no extra update sites are needed.
- Network access to the server hosting the data. Both `https://` and `s3://` URLs are supported.

## Folder layout

```
tools/ome-zarr/
├── README.md                  ← this page
├── TESTING.md                 ← test plan and results log
└── fiji/
    ├── crop-remote-zarr-macro/
    │   ├── README.md
    │   └── crop_remote_zarr.ijm
    └── crop-remote-zarr-bdv/
        ├── README.md
        └── crop_remote_zarr_bdv.groovy
```

To add a new tool, create a folder for it under `fiji/` (or `python/`, etc.), put a `README.md` in it using one of the existing pages as a template, and add it to the table above.
