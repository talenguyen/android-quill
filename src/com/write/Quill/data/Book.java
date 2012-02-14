package com.write.Quill.data;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.UUID;

import com.write.Quill.BookModifiedListener;
import com.write.Quill.data.TagManager.TagSet;

import name.vbraun.view.write.Page;

import junit.framework.Assert;

import android.content.Context;
import android.text.format.Time;
import android.util.Log;
import android.widget.Toast;

/**
 * A book is a collection of Pages and the tag manager together with some 
 * metadata like its title.
 * 
 * @author vbraun
 *
 */
public class Book {
	private static final String TAG = "Book";
	private static final String QUILL_DATA_FILE_SUFFIX = ".quill_data";
	private static final String INDEX_FILE = "index"+QUILL_DATA_FILE_SUFFIX;
	private static final String PAGE_FILE_PREFIX = "page_";
	protected static final String NOTEBOOK_DIRECTORY_PREFIX = "notebook_";

	private TagManager tagManager = new TagManager();

	public TagManager getTagManager() {
		return tagManager;
	}

	public TagSet getFilter() {
		return filter;
	}

	public void setFilter(TagSet newFilter) {
		filter = newFilter;
	}

	// dummy ctor for derived classes only
	protected Book() {
		allowSave = true;
	} 
	
	public Book(String description) {
		allowSave = true;
		pages.add(new Page(tagManager));
		ctime.setToNow();
		mtime.setToNow();
		uuid = UUID.randomUUID();
		title = description;
		loadingFinishedHook();
	}

	// Report page changes to the undo manager
	BookModifiedListener listener;

	public void setOnBookModifiedListener(BookModifiedListener newListener) {
		listener = newListener;
	}

	// /////////////////
	// persistent data

	// the unique identifier
	protected UUID uuid;

	// pages is never empty
	protected final LinkedList<Page> pages = new LinkedList<Page>();
	private TagSet filter = tagManager.newTagSet();
	protected int currentPage = 0;

	// The title
	protected String title = "Default Quill notebook";

	// creation and last modification time
	protected Time ctime = new Time();
	protected Time mtime = new Time();

	// end of persistent data
	// ///////////////////////

	// unset this to ensure that the book is never saved (truncated previews, for example)
	private boolean allowSave = false;

	// filteredPages is never empty
	protected final LinkedList<Page> filteredPages = new LinkedList<Page>();

	public LinkedList<Page> getFilteredPages() {
		return filteredPages;
	}

	public LinkedList<Page> getPages() {
		return pages;
	}

	// mark all subsequent pages as changed so that they are saved again
	private void touchAllSubsequentPages(int fromPage) {
		for (int i = fromPage; i < pages.size(); i++)
			getPage(i).touch();
	}

	private void touchAllSubsequentPages(Page fromPage) {
		touchAllSubsequentPages(pages.indexOf(fromPage));
	}

	private void touchAllSubsequentPages() {
		touchAllSubsequentPages(currentPage);
	}

	// Call this whenever the filter changed
	// will ensure that there is at least one page matching the filter
	// but will not change the current page (which need not match).
	public void filterChanged() {
		Page curr = currentPage();
		updateFilteredPages();
		Assert.assertTrue("current page must not change", curr == currentPage());
	}

	private void updateFilteredPages() {
		filteredPages.clear();
		ListIterator<Page> iter = pages.listIterator();
		while (iter.hasNext()) {
			Page p = iter.next();
			if (pageMatchesFilter(p))
				filteredPages.add(p);
		}
	}

	// remove empty pages as far as possible
	private void removeEmptyPages() {
		Page curr = currentPage();
		LinkedList<Page> empty = new LinkedList<Page>();
		ListIterator<Page> iter = pages.listIterator();
		while (iter.hasNext()) {
			Page p = iter.next();
			if (p == curr)
				continue;
			if (filteredPages.size() <= 1 && filteredPages.contains(p))
				continue;
			if (p.is_empty()) {
				empty.add(p);
				filteredPages.remove(p);
			}
		}
		iter = empty.listIterator();
		while (iter.hasNext()) {
			Page p = iter.next();
			requestRemovePage(p);
		}
		currentPage = pages.indexOf(curr);
		Assert.assertTrue("Current page removed?", currentPage >= 0);
	}

	// make sure the book and filteredPages is non-empty
	// call after every operation that potentially removed pages
	// the current page is not changed
	private void ensureNonEmpty(Page template) {
		Page curr = currentPage();
		Page new_page;
		if (template != null)
			new_page = new Page(template);
		else
			new_page = new Page(tagManager);
		new_page.tags.add(getFilter());
		requestAddPage(new_page, pages.size()); // pages.add(pages.size(),
												// new_page);
		setCurrentPage(curr);
		Assert.assertTrue("Missing tags?", pageMatchesFilter(new_page));
	}

	public String getTitle() {
		return title;
	}
	
	public void setTitle(String title) {
		this.title = title;
	}
	
	public UUID getUUID() {
		return uuid;
	}
	
	public Page getPage(int n) {
		return pages.get(n);
	}

	public int getPageNumber(Page page) {
		return pages.indexOf(page);
	}

	// to be called from the undo manager
	public void addPage(Page page, int position) {
		Assert.assertFalse("page already in book", pages.contains(page));
		touchAllSubsequentPages(position);
		pages.add(position, page);
		updateFilteredPages();
		currentPage = position;
	}

	// to be called from the undo manager
	public void removePage(Page page, int position) {
		Assert.assertTrue("page not in book", getPage(position) == page);
		int pos = filteredPages.indexOf(page);
		if (pos >= 0) {
			if (pos+1 < filteredPages.size()) { 
				pos = pages.indexOf(filteredPages.get(pos+1)) - 1;
			}
			else if (pos-1 >= 0)
				pos = pages.indexOf(filteredPages.get(pos-1));
			else
				pos = -1;
		}
		if (pos == -1) {
			if (position+1 < pages.size())
				pos = position + 1 - 1;
			else if (position-1 >= 0)
				pos = position - 1;
			else
				Assert.fail("Cannot create empty book");
		}
		pages.remove(position);
		updateFilteredPages();
		touchAllSubsequentPages(position);
		currentPage = pos;
		Log.d(TAG, "Removed page " + position + ", current = " + currentPage);
	}

	private void requestAddPage(Page page, int position) {
		if (listener == null)
			addPage(page, position);
		else
			listener.onPageInsertListener(page, position);
	}

	private void requestRemovePage(Page page) {
		int position = pages.indexOf(page);
		if (listener == null)
			removePage(page, position);
		else
			listener.onPageDeleteListener(page, position);
	}

	public boolean pageMatchesFilter(Page page) {
		ListIterator<TagManager.Tag> iter = getFilter().tagIterator();
		while (iter.hasNext()) {
			TagManager.Tag t = iter.next();
			if (!page.tags.contains(t)) {
				// Log.d(TAG, "does not match: "+t.name+" "+page.tags.size());
				return false;
			}
		}
		return true;
	}

	public int currentPageNumber() {
		return currentPage;
	}
	
	public Page currentPage() {
		// Log.v(TAG, "current_page() "+currentPage+"/"+pages.size());
		Assert.assertTrue(currentPage >= 0 && currentPage < pages.size());
		return pages.get(currentPage);
	}

	public void setCurrentPage(Page page) {
		currentPage = pages.indexOf(page);
		Assert.assertTrue(currentPage >= 0);
	}

	public int pagesSize() {
		return pages.size();
	}

	public int filteredPagesSize() {
		return filteredPages.size();
	}

	public Page getFilteredPage(int position) {
		return filteredPages.get(position);
	}

	// deletes page but makes sure that there is at least one page
	// the book always has at least one page.
	// deleting the last page is only clearing it etc.
	public void deletePage() {
		Log.d(TAG, "delete_page() " + currentPage + "/" + pages.size());
		Page page = currentPage();
		if (pages.size() == 1) {
			requestAddPage(new Page(page), 1);
		}
		requestRemovePage(page);
	}

	public Page lastPage() {
		Page last = filteredPages.getLast();
		currentPage = pages.indexOf(last);
		return last;
	}

	public Page lastPageUnfiltered() {
		currentPage = pages.size() - 1;
		return pages.get(currentPage);
	}

	public Page nextPage() {
		int pos = filteredPages.indexOf(currentPage());
		Page next = null;
		if (pos >= 0) {
			ListIterator<Page> iter = filteredPages.listIterator(pos);
			iter.next(); // == currentPage()
			if (iter.hasNext())
				next = iter.next();
		} else {
			ListIterator<Page> iter = pages.listIterator(currentPage);
			iter.next(); // == currentPage()
			while (iter.hasNext()) {
				Page p = iter.next();
				if (pageMatchesFilter(p)) {
					next = p;
					break;
				}
			}
		}
		if (next == null)
			return currentPage();
		currentPage = pages.indexOf(next);
		Assert.assertTrue(currentPage >= 0);
		return next;
	}

	public Page previousPage() {
		int pos = filteredPages.indexOf(currentPage());
		Page prev = null;
		if (pos >= 0) {
			ListIterator<Page> iter = filteredPages.listIterator(pos);
			if (iter.hasPrevious())
				prev = iter.previous();
			if (prev != null)
				Log.d(TAG, "Prev " + pos + " " + pageMatchesFilter(prev));
		} else {
			ListIterator<Page> iter = pages.listIterator(currentPage);
			while (iter.hasPrevious()) {
				Page p = iter.previous();
				if (pageMatchesFilter(p)) {
					prev = p;
					break;
				}
			}
		}
		if (prev == null)
			return currentPage();
		currentPage = pages.indexOf(prev);
		Assert.assertTrue(currentPage >= 0);
		return prev;
	}

	public Page nextPageUnfiltered() {
		if (currentPage + 1 < pages.size())
			currentPage += 1;
		return pages.get(currentPage);
	}

	public Page previousPageUnfiltered() {
		if (currentPage > 0)
			currentPage -= 1;
		return pages.get(currentPage);
	}

	// inserts a page at position and makes it the current page
	// empty pages are removed
	public Page insertPage(Page template, int position) {
		Page new_page;
		if (template != null)
			new_page = new Page(template);
		else
			new_page = new Page(tagManager);
		new_page.tags.add(getFilter());
		requestAddPage(new_page, position); // pages.add(position, new_page);
		removeEmptyPages();
		Assert.assertTrue("Missing tags?", pageMatchesFilter(new_page));
		Assert.assertTrue("wrong page", new_page == currentPage());
		return new_page;
	}

	public Page duplicatePage() {
		Page new_page;
		new_page = new Page(currentPage());
		new_page.strokes.addAll(currentPage().strokes);
		new_page.tags.add(getFilter());
		requestAddPage(new_page, currentPage + 1); 
		Assert.assertTrue("Missing tags?", pageMatchesFilter(new_page));
		Assert.assertTrue("wrong page", new_page == currentPage());
		return new_page;
	}
	
	public Page insertPage() {
		return insertPage(currentPage(), currentPage + 1);
	}

	public Page insertPageAtEnd() {
		return insertPage(currentPage(), pages.size());
	}

	public boolean isFirstPage() {
		if (filteredPages.isEmpty()) return false;
		return currentPage() == filteredPages.getFirst();
	}

	public boolean isLastPage() {
		if (filteredPages.isEmpty()) return false;
		return currentPage() == filteredPages.getLast();
	}

	public boolean isFirstPageUnfiltered() {
		return currentPage == 0;
	}

	public boolean isLastPageUnfiltered() {
		return currentPage + 1 == pages.size();
	}

	// ///////////////////////////////////////////////////
	// Input/Output
	
	public static class BookLoadException extends Exception {
		public BookLoadException(String string) {
			super(string);
		}
		private static final long serialVersionUID = -4727997764997002754L;		
	}
	
	public static class BookSaveException extends Exception {
		public BookSaveException(String string) {
			super(string);
		}
		private static final long serialVersionUID = -7622965955861362254L;
	}

	// Pick a current page if it is out of bounds
	private void makeCurrentPageConsistent() {
		if (currentPage < 0)
			currentPage = 0;
		if (currentPage >= pages.size())
			currentPage = pages.size() - 1;
		if (pages.isEmpty()) {
			Page p = new Page(tagManager);
			p.tags.add(getFilter());
			pages.add(p);
			currentPage = 0;
		}
	}

	// this is always called after the book was loaded
	protected void loadingFinishedHook() {
		makeCurrentPageConsistent();
		filterChanged();
	}
	
	////////////////////////////////////////
	/// Load and save app private data 

	// Loads the book. This is the complement to the save() method
	public Book(Context context, UUID uuid) {
		allowSave = true;
		File dir = new File(context.getFilesDir(), NOTEBOOK_DIRECTORY_PREFIX+getUUID().toString());
		try {
			doLoadBookFromDirectory(dir, -1);
		} catch (BookLoadException e ) {
			Log.e(TAG, e.getMessage());
			Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG);
		} catch (IOException e ) {
			Log.e(TAG, e.getLocalizedMessage());
			Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG);
		}
		loadingFinishedHook();
	}
	
	// Load a truncated preview of the book
	public Book(Context context, UUID uuid, int pageLimit) {
		allowSave = false;
		File dir = new File(context.getFilesDir(), NOTEBOOK_DIRECTORY_PREFIX+getUUID().toString());
		try {
			doLoadBookFromDirectory(dir, -1);
		} catch (BookLoadException e ) {
			Log.e(TAG, e.getMessage());
			Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG);
		} catch (IOException e ) {
			Log.e(TAG, e.getLocalizedMessage());
			Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG);
		}
		loadingFinishedHook();
	}



	// save data internally. To load, use the constructor.
	public void save(Context context) {
		Assert.assertTrue(allowSave);
		File dir = new File(context.getFilesDir(), NOTEBOOK_DIRECTORY_PREFIX+getUUID().toString());
		try {
			doSaveBookInDirectory(dir);
		} catch (BookSaveException e ) {
			Log.e(TAG, e.getMessage());
			Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG);
		} catch (IOException e ) {
			Log.e(TAG, e.getLocalizedMessage());
			Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG);
		}
	}
		
	private void doLoadBookFromDirectory(File dir, int pageLimit) throws BookLoadException, IOException {
		if (!dir.isDirectory())
			throw new BookLoadException("No such directory: "+dir.toString());
		LinkedList<UUID> pageUUIDs = loadIndex(dir);
		pages.clear();
		for (UUID uuid : pageUUIDs) {
			if (pageLimit >=0 && pages.size() >= pageLimit) return;
			loadPage(uuid, dir);
		}
		// TODO: add any pages not in the index
	}
	
	private void doSaveBookInDirectory(File dir) throws BookSaveException, IOException {
		if (!dir.isDirectory() && !dir.mkdir())
			throw new BookSaveException("Error creating directory "+dir.toString());
		saveIndex(dir);
		for (Page page : getPages()) {
			if (!page.isModified())	continue;
			savePage(page, dir);
		}
		// TODO: delete pages whose UUID is not used
	}

	
	////////////////////////////////////////
	/// Load and save archives 
	
	public void saveBookArchive(File file) throws BookSaveException {
		Assert.assertTrue(allowSave);
		try {
			doSaveBookArchive(file);
		} catch (IOException e) {
			throw new BookSaveException(e.getLocalizedMessage());
		}
	}

	// Load an archive; the complement to saveArchive
	public Book(File file) throws BookLoadException {
		allowSave = true;
		try {
			doLoadBookArchive(file, -1);
		} catch (IOException e) {
			throw new BookLoadException(e.getLocalizedMessage());
		}
		loadingFinishedHook();
	}

	// Peek an archive: load index data but skip pages except for the first couple of pages
	public Book(File file, int pageLimit) throws BookLoadException {
		allowSave = false; // just to be sure that we don't save truncated data back
		try {
			doLoadBookArchive(file, pageLimit);
		} catch (IOException e) {
			throw new BookLoadException(e.getLocalizedMessage());
		}
		currentPage = 0;
		loadingFinishedHook();
	}
	
	/** Internal helper to load book from archive file
	 * @param file The achive file to load
	 * @param pageLimit when to stop loading pages. Negative values mean load all pages.
	 * @throws IOException, BookLoadException
	 */
	private void doLoadBookArchive(File file, int pageLimit) throws IOException, BookLoadException {
		FileInputStream fis = null;
		BufferedInputStream buffer = null;
		DataInputStream dataIn = null;
		try {
			fis = new FileInputStream(file);
			buffer = new BufferedInputStream(fis);
			dataIn = new DataInputStream(buffer);
			pages.clear();
			LinkedList<UUID> pageUUIDs = loadIndex(dataIn);
			for (UUID uuid : pageUUIDs) {
				if (pageLimit >=0 && pages.size() >= pageLimit) return;
				pages.add(loadPage(dataIn));
			}
		} finally {
			if (dataIn != null) dataIn.close();
			if (buffer != null) buffer.close();
			if (fis != null) fis.close();
		}
	}

	private void doSaveBookArchive(File file) throws IOException, BookSaveException {
		FileOutputStream fos = null;
		BufferedOutputStream buffer = null;
		DataOutputStream dataOut = null;
		try {
			fos = new FileOutputStream(file);
			buffer = new BufferedOutputStream(fos);
			dataOut = new DataOutputStream(buffer);
			saveIndex(dataOut);
			for (Page page : pages)
				savePage(page, dataOut);
		} finally {
			if (dataOut != null) dataOut.close();
			if (buffer != null) buffer.close();
			if (fos != null) fos.close();
		}
	}

	/////////////////////////////
	/// implementation of load/save
	
	private LinkedList<UUID> loadIndex(File dir) throws IOException, BookLoadException {
		File indexFile = new File(dir, INDEX_FILE);
		FileInputStream fis = null;
		BufferedInputStream buffer = null;
		DataInputStream dataIn = null;
		try {
			fis = new FileInputStream(indexFile);
			buffer = new BufferedInputStream(fis);
			dataIn = new DataInputStream(buffer);
			return loadIndex(dataIn);
		} finally {
			if (dataIn != null) dataIn.close();
			if (buffer != null) buffer.close();
			if (fis != null) fis.close();
		}
	}

	private void saveIndex(File dir) throws IOException, BookSaveException {
		File indexFile = new File(dir, INDEX_FILE);
		FileOutputStream fos = null;
		BufferedOutputStream buffer = null;
		DataOutputStream dataOut = null;
		try {
			fos = new FileOutputStream(indexFile);
			buffer = new BufferedOutputStream(fos);
			dataOut = new DataOutputStream(buffer);
			saveIndex(dataOut);
			dataOut.close();
		} finally {
			if (dataOut != null) dataOut.close();
			if (buffer != null) buffer.close();
			if (fos != null) fos.close();
		}			
	}

	private LinkedList<UUID> loadIndex(DataInputStream dataIn) throws IOException, BookLoadException {
		Log.d(TAG, "Loading book index");
		int n_pages;
		LinkedList<UUID> pageUuidList = new LinkedList<UUID>();
		int version = dataIn.readInt();
		if (version == 3) {
			n_pages = dataIn.readInt();
			for (int i=0; i<n_pages; i++)
				pageUuidList.add(UUID.fromString(dataIn.readUTF()));
			currentPage = dataIn.readInt();
			title = dataIn.readUTF();
			ctime.set(dataIn.readLong());
			mtime.set(dataIn.readLong());
			uuid = UUID.fromString(dataIn.readUTF());
			setFilter(tagManager.loadTagSet(dataIn));
		} else if (version == 3) {
			n_pages = dataIn.readInt();
			currentPage = dataIn.readInt();
			title = dataIn.readUTF();
			ctime.set(dataIn.readLong());
			mtime.set(dataIn.readLong());
			uuid = UUID.fromString(dataIn.readUTF());
			setFilter(tagManager.loadTagSet(dataIn));
		} else if (version == 2) {
			n_pages = dataIn.readInt();
			currentPage = dataIn.readInt();
			title = "Imported Quill notebook v2";
			ctime.setToNow();
			mtime.setToNow();
			uuid = UUID.randomUUID();
			setFilter(tagManager.loadTagSet(dataIn));
		} else if (version == 1) {
			n_pages = dataIn.readInt();
			currentPage = dataIn.readInt();
			title = "Imported Quill notebook v1";
			ctime.setToNow();
			mtime.setToNow();
			uuid = UUID.randomUUID();
			setFilter(tagManager.newTagSet());
		} else 
			throw new BookLoadException("Unknown version in load_index()");
		return pageUuidList;
	}

	private void saveIndex(DataOutputStream dataOut) throws IOException {
		Log.d(TAG, "Saving book index");
		dataOut.writeInt(4);
		dataOut.writeInt(pages.size());
		for (int i=0; i<pages.size(); i++)
			dataOut.writeUTF(getPage(i).getUUID().toString());
		dataOut.writeInt(currentPage);
		dataOut.writeUTF(title);
		dataOut.writeLong(ctime.toMillis(false));
		mtime.setToNow();
		dataOut.writeLong(mtime.toMillis(false));
		dataOut.writeUTF(uuid.toString());
		getFilter().write_to_stream(dataOut);
	}

	private Page loadPage(UUID uuid, File dir) throws IOException {
		File file = new File(dir, PAGE_FILE_PREFIX + uuid.toString() + QUILL_DATA_FILE_SUFFIX);
		FileInputStream fis = null;
		BufferedInputStream buffer = null;
		DataInputStream dataIn = null;
		try {
			fis = new FileInputStream(file);
			buffer = new BufferedInputStream(fis);
			dataIn = new DataInputStream(buffer);
			return loadPage(dataIn);
		} finally {
			if (dataIn != null) dataIn.close();
			if (buffer != null) buffer.close();
			if (fis != null) fis.close();
		}
	}

	private void savePage(Page page, File dir) throws IOException {
		File file = new File(dir, PAGE_FILE_PREFIX + uuid.toString() + QUILL_DATA_FILE_SUFFIX);
		FileOutputStream fos = null;
		BufferedOutputStream buffer = null;
		DataOutputStream dataOut = null;
		try {
			fos = new FileOutputStream(file);
			buffer = new BufferedOutputStream(fos);
			dataOut = new DataOutputStream(buffer);
			savePage(page, dataOut);
		} finally {
			if (dataOut != null) dataOut.close();
			if (buffer != null) buffer.close();
			if (fos != null) fos.close();
		}
	}

	private Page loadPage(DataInputStream dataIn) throws IOException {
		Page page = new Page(dataIn, tagManager);
		Log.d(TAG, "Loaded book page "+page.getUUID());
		return page;
	}

	private void savePage(Page page, DataOutputStream dataOut) throws IOException {
		Log.d(TAG, "Saving book page "+page.getUUID());
		page.writeToStream(dataOut);
	}

}
