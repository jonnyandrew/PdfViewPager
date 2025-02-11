/*
 * Copyright (C) 2016 Olmo Gallegos Hernández.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package es.voghdev.pdfviewpager.library.adapter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import es.voghdev.pdfviewpager.library.R;

public class BasePDFPagerAdapter extends PagerAdapter {
    protected static final int FIRST_PAGE = 0;
    protected static final float DEFAULT_QUALITY = 2.0f;
    protected static final int DEFAULT_OFFSCREENSIZE = 1;

    protected Iterable<String> pdfPaths;
    protected Context context;
    protected List<PdfRenderer> renderers;
    protected List<BitmapContainer> bitmapContainers;
    protected LayoutInflater inflater;

    protected float renderQuality;
    protected int offScreenSize;

    public BasePDFPagerAdapter(Context context, String... pdfPaths) throws IOException {
        this.pdfPaths = Arrays.asList(pdfPaths);
        this.context = context;
        this.renderQuality = DEFAULT_QUALITY;
        this.offScreenSize = DEFAULT_OFFSCREENSIZE;

        init();
    }

    /**
     * This constructor was added for those who want to customize ViewPager's offScreenSize attr
     */
    public BasePDFPagerAdapter(Context context, Iterable<String> pdfPaths, int offScreenSize) throws IOException {
        this.pdfPaths = pdfPaths;
        this.context = context;
        this.renderQuality = DEFAULT_QUALITY;
        this.offScreenSize = offScreenSize;

        init();
    }

    @SuppressWarnings("NewApi")
    protected void init() throws IOException {
        renderers = new ArrayList<>();
        bitmapContainers = new ArrayList<>();
        for (String pdfPath : pdfPaths) {
            PdfRenderer renderer = new PdfRenderer(getSeekableFileDescriptor(pdfPath));
            renderers.add(renderer);
            PdfRendererParams params = extractPdfParamsFromFirstPage(renderer, renderQuality);
            bitmapContainers.add(new SimpleBitmapPool(params));
        }
        inflater = (LayoutInflater) context.getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
    }

    @SuppressWarnings("NewApi")
    protected PdfRendererParams extractPdfParamsFromFirstPage(PdfRenderer renderer, float renderQuality) {
        PdfRenderer.Page samplePage = getPDFPage(renderer, FIRST_PAGE);
        PdfRendererParams params = new PdfRendererParams();

        params.setRenderQuality(renderQuality);
        params.setOffScreenSize(offScreenSize);
        params.setWidth((int) (samplePage.getWidth() * renderQuality));
        params.setHeight((int) (samplePage.getHeight() * renderQuality));

        samplePage.close();

        return params;
    }

    protected ParcelFileDescriptor getSeekableFileDescriptor(String path) throws IOException {
        ParcelFileDescriptor parcelFileDescriptor;

        File pdfCopy = new File(path);

        if (pdfCopy.exists()) {
            parcelFileDescriptor = ParcelFileDescriptor.open(pdfCopy, ParcelFileDescriptor.MODE_READ_ONLY);
            return parcelFileDescriptor;
        }

        if (isAnAsset(path)) {
            pdfCopy = new File(context.getCacheDir(), path);
            parcelFileDescriptor = ParcelFileDescriptor.open(pdfCopy, ParcelFileDescriptor.MODE_READ_ONLY);
        } else {
            URI uri = URI.create(String.format("file://%s", path));
            parcelFileDescriptor = context.getContentResolver().openFileDescriptor(Uri.parse(uri.toString()), "rw");
        }

        return parcelFileDescriptor;
    }

    protected boolean isAnAsset(String path) {
        return !path.startsWith("/");
    }

    @Override
    @SuppressWarnings("NewApi")
    public Object instantiateItem(ViewGroup container, int position) {
        View v = inflater.inflate(R.layout.view_pdf_page, container, false);
        ImageView iv = (ImageView) v.findViewById(R.id.imageView);

        if (getCount() < position) {
            return v;
        }

        LocalPosition localPosition = getLocalPosition(position);
        PdfRenderer.Page page = getPDFPage(renderers.get(localPosition.pdfIndex), localPosition.pageIndex);

        Bitmap bitmap = bitmapContainers
                .get(localPosition.pdfIndex)
                .get(localPosition.pageIndex);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();

        iv.setImageBitmap(bitmap);
        v.setTag(getTag(position));
        ((ViewPager) container).addView(v, 0);

        return v;
    }

    @SuppressWarnings("NewApi")
    PdfRenderer.Page getPDFPage(PdfRenderer renderer, int position) {
        return renderer.openPage(position);
    }

    public LocalPosition getLocalPosition(int globalPosition) {
        Iterator<PdfRenderer> rendererIterator = renderers.iterator();
        int pdfIndex = 0;
        int localPageIndex = globalPosition;
        while (rendererIterator.hasNext()) {
            PdfRenderer curRenderer = rendererIterator.next();

            if (localPageIndex < curRenderer.getPageCount()) {
                return new LocalPosition(pdfIndex, localPageIndex);
            }

            pdfIndex++;
            localPageIndex -= curRenderer.getPageCount();
        }

        throw new IndexOutOfBoundsException();
    }

    public int getGlobalPosition(LocalPosition localPosition) {
        int globalPosition = 0;

        if (localPosition.pdfIndex >= renderers.size()) {
            throw new IndexOutOfBoundsException(
                    String.format(
                            "PDF index (%d) is greater than number of PDFs (%d)",
                            localPosition.pdfIndex,
                            renderers.size()
                    )
            );
        }

        if (localPosition.pageIndex >= renderers.get(localPosition.pdfIndex).getPageCount()) {
            throw new IndexOutOfBoundsException(
                    String.format(
                            "Page index (%d) is greater than the PDF page count (%d)",
                            localPosition.pageIndex,
                            renderers.get(localPosition.pdfIndex).getPageCount()
                    )
            );
        }

        for (int i = 0; i < localPosition.pdfIndex; i++) {
            globalPosition += renderers.get(i).getPageCount();
        }

        globalPosition += localPosition.pageIndex;

        return globalPosition;
    }

    public String getTag(int globalPosition) {
        return String.valueOf(globalPosition);
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        // bitmap.recycle() causes crashes if called here.
        // All bitmaps are recycled in close().
    }

    @SuppressWarnings("NewApi")
    public void close() {
        releaseAllBitmaps();
        for (PdfRenderer renderer : renderers) {
            renderer.close();
        }
    }

    protected void releaseAllBitmaps() {
        for (BitmapContainer bitmapContainer : bitmapContainers) {
            bitmapContainer.clear();
        }
    }

    @Override
    @SuppressWarnings("NewApi")
    public int getCount() {
        int count = 0;

        for (PdfRenderer renderer : renderers) {
            count += renderer.getPageCount();
        }

        return count;
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == (View) object;
    }
}
