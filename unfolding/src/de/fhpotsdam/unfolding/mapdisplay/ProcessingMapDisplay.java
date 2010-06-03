package de.fhpotsdam.unfolding.mapdisplay;

import java.util.Collections;
import java.util.Vector;

import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PGraphics;
import processing.core.PImage;
import processing.core.PMatrix2D;
import processing.core.PVector;
import de.fhpotsdam.unfolding.Map;
import de.fhpotsdam.unfolding.core.Coordinate;
import de.fhpotsdam.unfolding.geo.Location;
import de.fhpotsdam.unfolding.providers.AbstractMapProvider;
import de.fhpotsdam.unfolding.providers.Microsoft;

public class ProcessingMapDisplay extends AbstractMapDisplay implements PConstants {

	private static final boolean debugBorder = false;

	// Used for loadImage and float maths
	public PApplet papplet;

	// Offset of the mapDisplay (in world coordinates).
	protected float offsetX;
	protected float offsetY;

	/** default to Microsoft Hybrid */
	public ProcessingMapDisplay(PApplet papplet) {
		this(papplet, new Microsoft.HybridProvider());
	}

	/** new mapDisplay using applet width and height, and given provider */
	public ProcessingMapDisplay(PApplet papplet, AbstractMapProvider provider) {
		this(papplet, provider, 0, 0, papplet.width, papplet.height);
	}

	/**
	 * make a new interactive mapDisplay, using the given provider, of the given width and height
	 */
	public ProcessingMapDisplay(PApplet papplet, AbstractMapProvider provider, float offsetX,
			float offsetY, float width, float height) {
		super(provider, width, height);
		this.papplet = papplet;
		this.offsetX = offsetX;
		this.offsetY = offsetY;
	}

	// PROJECTIONS - using PMatrix2D
	// REVISIT This seems strange to be in here (duplicate matrices, and such)
	public PVector locationPoint(Location location) {
		PMatrix2D m = new PMatrix2D();
		m.translate(offsetX + width / 2, offsetY + height / 2);
		m.scale((float) sc);
		m.translate((float) tx, (float) ty);

		Coordinate coord = provider.locationCoordinate(location).zoomTo(0);
		float[] out = new float[2];
		m.mult(new float[] { coord.column * TILE_WIDTH, coord.row * TILE_HEIGHT }, out);

		return new PVector(out[0], out[1]);
	}

	public Location pointLocation(PVector point) {
		return pointLocation(point.x, point.y);
	}

	public Location pointLocation(float x, float y) {
		// TODO: create this matrix once and keep it around for drawing and
		// projecting
		PMatrix2D m = new PMatrix2D();
		m.translate(offsetX + width / 2, offsetY + height / 2);
		m.scale((float) sc);
		m.translate((float) tx, (float) ty);

		// find top left and bottom right positions of mapDisplay in screenspace:
		float tl[] = new float[2];
		m.mult(new float[] { 0, 0 }, tl);
		float br[] = new float[2];
		m.mult(new float[] { TILE_WIDTH, TILE_HEIGHT }, br);

		float col = (x - tl[0]) / (br[0] - tl[0]);
		float row = (y - tl[1]) / (br[1] - tl[1]);
		Coordinate coord = new Coordinate(row, col, 0);

		return provider.coordinateLocation(coord);
	}

	protected PGraphics getPG() {
		return papplet.g;
	}

	protected void postDraw() {
		// May be implemented in sub-classes.
	}

	/** draw the mapDisplay on the given PApplet */
	public void draw() {
		PGraphics pg = getPG();

		// Store and switch off smooth (OpenGL cannot handle it)
		boolean smooth = papplet.g.smooth;
		pg.noSmooth();

		// translate and scale, from the middle
		pg.pushMatrix();
		pg.translate(width / 2, height / 2);
		pg.scale((float) sc);
		pg.translate((float) tx, (float) ty);

		// find the bounds of the ur-tile in screen-space:
		float minX = pg.screenX(0, 0);
		float minY = pg.screenY(0, 0);
		float maxX = pg.screenX(TILE_WIDTH, TILE_HEIGHT);
		float maxY = pg.screenY(TILE_WIDTH, TILE_HEIGHT);

		// PApplet.println("min(" + minX + "," + minY + ") max(" + maxX + "," + maxY + ")");

		Vector visibleKeys = getVisibleKeys(minX, minY, maxX, maxY);

		if (visibleKeys.size() > 0) {
			Coordinate previous = (Coordinate) visibleKeys.get(0);
			pg.pushMatrix();
			pg.scale(1.0f / PApplet.pow(2, previous.zoom));

			for (int i = 0; i < visibleKeys.size(); i++) {
				Coordinate coord = (Coordinate) visibleKeys.get(i);
				if (coord.zoom != previous.zoom) {
					pg.popMatrix();
					pg.pushMatrix();
					pg.scale(1.0f / PApplet.pow(2, coord.zoom));
				}

				if (images.containsKey(coord)) {
					PImage tile = (PImage) images.get(coord);

					float x = coord.column * TILE_WIDTH;
					float y = coord.row * TILE_HEIGHT;

					if (debugBorder) {
						pg.strokeWeight(2);
						pg.stroke(255, 0, 0, 100);
						pg.rect(x, y, TILE_WIDTH, TILE_HEIGHT);
						pg.noStroke();
					}
					pg.image(tile, x, y, TILE_WIDTH, TILE_HEIGHT);

					if (recent_images.contains(tile)) {
						recent_images.remove(tile);
					}
					recent_images.add(tile);
				}
			}
			pg.popMatrix();
		}
		pg.popMatrix();

		cleanupImageBuffer();

		postDraw();

		// restore smoothing, if needed
		if (smooth) {
			papplet.smooth();
		}
	}

	/**
	 * Cleans oldest images if too many images exist.
	 * 
	 * Tiles are added to the recency-based list to allow removing oldest ones from images-array.
	 */
	protected void cleanupImageBuffer() {
		if (recent_images.size() > max_images_to_keep) {
			recent_images.subList(0, recent_images.size() - max_images_to_keep).clear();
			images.values().retainAll(recent_images);
		}

	}

	protected Vector getVisibleKeys(float minX, float minY, float maxX, float maxY) {

		// what power of 2 are we at?
		// 0 when scale is around 1, 1 when scale is around 2,
		// 2 when scale is around 4, 3 when scale is around 8, etc.
		// Till: NB Using int zoom levels to use correct tile (between-zoom values are scaled in
		// Processing)
		int zoomLevel = Map.getZoomLevelFromScale((float) sc);

		// how many columns and rows of tiles at this zoom?
		// (this is basically (int)sc, but let's derive from zoom to be sure
		int cols = (int) Map.getScaleFromZoom(zoomLevel);
		int rows = (int) Map.getScaleFromZoom(zoomLevel);

		// find the biggest box the screen would fit in:, aligned with the mapDisplay:
		float screenMinX = 0;
		float screenMinY = 0;
		float screenMaxX = width - TILE_WIDTH;
		float screenMaxY = height - TILE_HEIGHT;

		// TODO: align this, and fix the next bit to work with rotated maps

		// find start and end columns
		int minCol = (int) PApplet.floor(cols * (screenMinX - minX) / (maxX - minX));
		int maxCol = (int) PApplet.ceil(cols * (screenMaxX - minX) / (maxX - minX));
		int minRow = (int) PApplet.floor(rows * (screenMinY - minY) / (maxY - minY));
		int maxRow = (int) PApplet.ceil(rows * (screenMaxY - minY) / (maxY - minY));

		// pad a bit, for luck (well, because we might be zooming out between
		// zoom levels)
		minCol -= grid_padding;
		minRow -= grid_padding;
		maxCol += grid_padding;
		maxRow += grid_padding;

		// we don't wrap around the world yet, so:
		minCol = PApplet.constrain(minCol, 0, cols);
		maxCol = PApplet.constrain(maxCol, 0, cols);
		minRow = PApplet.constrain(minRow, 0, rows);
		maxRow = PApplet.constrain(maxRow, 0, rows);

		// keep track of what we can see already:
		Vector visibleKeys = new Vector();

		// grab coords for visible tiles
		for (int col = minCol; col <= maxCol; col++) {
			for (int row = minRow; row <= maxRow; row++) {

				// source coordinate wraps around the world:
				Coordinate coord = provider.sourceCoordinate(new Coordinate(row, col, zoomLevel));
				// make sure we still have ints:
				coord.roundValues();

				// keep this for later:
				visibleKeys.add(coord);

				if (!images.containsKey(coord)) {
					// fetch it if we don't have it
					grabTile(coord);

					// see if we have a parent coord for this tile?
					boolean gotParent = false;
					for (int i = (int) coord.zoom; i > 0; i--) {
						Coordinate zoomed = coord.zoomTo(i).container();
						zoomed.roundValues();
						if (images.containsKey(zoomed)) {
							visibleKeys.add(zoomed);
							gotParent = true;
							break;
						}
					}

					// or if we have any of the children
					if (!gotParent) {
						Coordinate zoomed = coord.zoomBy(1).container();
						Coordinate[] kids = { zoomed, zoomed.right(), zoomed.down(),
								zoomed.right().down() };
						for (int i = 0; i < kids.length; i++) {
							zoomed = kids[i];
							// make sure we still have ints:
							zoomed.row = PApplet.round(zoomed.row);
							zoomed.column = PApplet.round(zoomed.column);
							zoomed.zoom = PApplet.round(zoomed.zoom);
							if (images.containsKey(zoomed)) {
								visibleKeys.add(zoomed);
							}
						}
					}

				}

			} // rows
		} // columns

		// sort by zoom so we draw small zoom levels (big tiles) first:
		Collections.sort(visibleKeys, zoomComparator);

		// stop fetching things we can't see:
		// (visibleKeys also has the parents and children, if needed, but that
		// shouldn't matter)
		queue.retainAll(visibleKeys);

		// sort what's left by distance from center:
		queueSorter.setCenter(new Coordinate((minRow + maxRow) / 2.0f, (minCol + maxCol) / 2.0f,
				zoomLevel));
		Collections.sort(queue, queueSorter);

		// load up to 4 more things:
		processQueue();

		return visibleKeys;
	}

	// TILE LOADING ---------------------------------------

	protected TileLoader createTileLoader(Coordinate coord) {
		return new ProcessingTileLoader(coord);
	}

	/**
	 * for tile loader threads to load PImages
	 */
	public class ProcessingTileLoader implements TileLoader, Runnable {
		Coordinate coord;

		ProcessingTileLoader(Coordinate coord) {
			this.coord = coord;
		}

		public void run() {
			String[] urls = provider.getTileUrls(coord);
			// use unknown to let loadImage decide
			PImage img = papplet.loadImage(urls[0], "unknown");
			if (img != null) {
				for (int i = 1; i < urls.length; i++) {
					PImage img2 = papplet.loadImage(urls[i], "unknown");
					if (img2 != null) {
						img.blend(img2, 0, 0, img.width, img.height, 0, 0, img.width, img.height,
								BLEND);
					}
				}
			}
			tileDone(coord, img);
		}
	}
}