/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2012 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package org.appcelerator.titanium.view;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.TreeSet;

import org.appcelerator.kroll.common.Log;
import org.appcelerator.titanium.TiApplication;
import org.appcelerator.titanium.TiC;
import org.appcelerator.titanium.TiDimension;
import org.appcelerator.titanium.TiLaunchActivity;
import org.appcelerator.titanium.proxy.TiViewProxy;
import org.appcelerator.titanium.util.TiUIHelper;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.OnHierarchyChangeListener;

/**
 * Base layout class for all Titanium views. 
 */
public class TiCompositeLayout extends ViewGroup
	implements OnHierarchyChangeListener
{
	/**
	 * Supported layout arrangements
	 * @module.api
	 */
	public enum LayoutArrangement {
		/**
		 * The default Titanium layout arrangement.
		 */
		DEFAULT,
		/**
		 * The layout arrangement for Views and Windows that set layout: "vertical".
		 */
		VERTICAL,
		/**
		 * The layout arrangement for Views and Windows that set layout: "horizontal".
		 */
		HORIZONTAL
	}

	protected static final String TAG = "TiCompositeLayout";

	public static final int NOT_SET = Integer.MIN_VALUE;

	private TreeSet<View> viewSorter;
	private boolean needsSort;
	protected LayoutArrangement arrangement;
	
	// Used by horizonal arrangement calculations
	private int horizontalLayoutTopBuffer = 0;
	private int horizontalLayoutCurrentLeft = 0;
	private int horizontalLayoutLineHeight = 0;
	private boolean enableHorizontalWrap = true;
	private int horizontalLayoutLastIndexBeforeWrap = 0;
	private int horiztonalLayoutPreviousRight = 0;

	private float alpha = 1.0f;
	private Method setAlphaMethod;

	private WeakReference<TiViewProxy> proxy;
	private static final int HAS_SIZE_FILL_CONFLICT = 1;
	private static final int NO_SIZE_FILL_CONFLICT = 2;

	// We need these two constructors for backwards compatibility with modules

	/**
	 * Constructs a new TiCompositeLayout object.
	 * @param context the associated context.
	 * @module.api
	 */
	public TiCompositeLayout(Context context)
	{
		this(context, LayoutArrangement.DEFAULT, null);
	}

	/**
	 * Constructs a new TiCompositeLayout object.
	 * @param context the associated context.
	 * @param arrangement the associated LayoutArrangement
	 * @module.api
	 */
	public TiCompositeLayout(Context context, LayoutArrangement arrangement)
	{
		this(context, LayoutArrangement.DEFAULT, null);
	}

	/**
	 * Constructs a new TiCompositeLayout object.
	 * @param context the associated context.
	 * @param proxy the associated proxy.
	 */
	public TiCompositeLayout(Context context, TiViewProxy proxy)
	{
		this(context, LayoutArrangement.DEFAULT, proxy);
	}

	/**
	 * Constructs a new TiCompositeLayout object.
	 * @param context the associated context.
	 * @param arrangement the associated LayoutArrangement
	 * @param proxy the associated proxy.
	 */
	public TiCompositeLayout(Context context, LayoutArrangement arrangement, TiViewProxy proxy)
	{
		super(context);
		this.arrangement = arrangement;
		
		if (arrangement == LayoutArrangement.DEFAULT) {
			android.util.Log.d("set TiCompositeLayout arrangement", "DEFAULT");
		} else if (arrangement == LayoutArrangement.HORIZONTAL) {
			android.util.Log.d("set TiCompositeLayout arrangement", "HORIZONTAL");
		} else if (arrangement == LayoutArrangement.VERTICAL) {
			android.util.Log.d("set TiCompositeLayout arrangement", "VERTICAL");
		}
		
		
		this.viewSorter = new TreeSet<View>(new Comparator<View>()
		{

			public int compare(View o1, View o2)
			{
				TiCompositeLayout.LayoutParams p1 = (TiCompositeLayout.LayoutParams) o1.getLayoutParams();
				TiCompositeLayout.LayoutParams p2 = (TiCompositeLayout.LayoutParams) o2.getLayoutParams();

				int result = 0;

				if (p1.optionZIndex != NOT_SET && p2.optionZIndex != NOT_SET) {
					if (p1.optionZIndex < p2.optionZIndex) {
						result = -1;
					} else if (p1.optionZIndex > p2.optionZIndex) {
						result = 1;
					}
				} else if (p1.optionZIndex != NOT_SET) {
					if (p1.optionZIndex < 0) {
						result = -1;
					}
					if (p1.optionZIndex > 0) {
						result = 1;
					}
				} else if (p2.optionZIndex != NOT_SET) {
					if (p2.optionZIndex < 0) {
						result = 1;
					}
					if (p2.optionZIndex > 0) {
						result = -1;
					}
				}

				if (result == 0) {
					if (p1.index < p2.index) {
						result = -1;
					} else if (p1.index > p2.index) {
						result = 1;
					} else {
						throw new IllegalStateException("Ambiguous Z-Order");
					}
				}

				return result;
			}
		});

		needsSort = true;
		setOnHierarchyChangeListener(this);
		this.proxy = new WeakReference<TiViewProxy>(proxy);
	}

	private String viewToString(View view) {
		return view.getClass().getSimpleName() + "@" + Integer.toHexString(view.hashCode());
	}
	
	public void resort()
	{
		needsSort = true;
		requestLayout();
		invalidate();
	}

	public void onChildViewAdded(View parent, View child) {
		needsSort = true;
		if (parent != null && child != null) {
			Log.d(TAG, "Attaching: " + viewToString(child) + " to " + viewToString(parent), Log.DEBUG_MODE);
		}
	}

	public void onChildViewRemoved(View parent, View child) {
		needsSort = true;
		Log.d(TAG, "Removing: " + viewToString(child) + " from " + viewToString(parent), Log.DEBUG_MODE);
	}

	@Override
	protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
		return p instanceof TiCompositeLayout.LayoutParams;
	}

	@Override
	protected LayoutParams generateDefaultLayoutParams()
	{
		// Default behavior is size since optionWidth/optionHeight is null, and autoFillsWidth/autoFillsHeight is false.
		// Some classes such as ViewProxy will set autoFillsWidth/autoFillsHeight to true in order to trigger the fill
		// behavior by default.
		LayoutParams params = new LayoutParams();
		params.optionLeft = null;
		params.optionRight = null;
		params.optionTop = null;
		params.optionBottom = null;
		params.optionZIndex = NOT_SET;
		params.sizeOrFillHeightEnabled = true;
		params.sizeOrFillWidthEnabled = true;

		return params;
	}

	private static int getAsPercentageValue(double percentage, int value)
	{
		return (int) Math.round((percentage / 100.0) * value);
	}

	protected int getViewWidthPadding(View child, int parentWidth)
	{
		LayoutParams p = (LayoutParams) child.getLayoutParams();
		int padding = 0;
		if (p.optionLeft != null) {
			if (p.optionLeft.isUnitPercent()) {
				padding += getAsPercentageValue(p.optionLeft.getValue(), parentWidth);
			} else {
				padding += p.optionLeft.getAsPixels(this);
			}
		}
		if (p.optionRight != null) {
			if (p.optionRight.isUnitPercent()) {
				padding += getAsPercentageValue(p.optionRight.getValue(), parentWidth);
			} else {
				padding += p.optionRight.getAsPixels(this);
			}
		}
		return padding;
	}

	protected int getViewHeightPadding(View child, int parentHeight)
	{
		LayoutParams p = (LayoutParams) child.getLayoutParams();
		int padding = 0;
		if (p.optionTop != null) {
			if (p.optionTop.isUnitPercent()) {
				padding += getAsPercentageValue(p.optionTop.getValue(), parentHeight);
			} else {
				padding += p.optionTop.getAsPixels(this);
			}
		}
		if (p.optionBottom != null) {
			if (p.optionBottom.isUnitPercent()) {
				padding += getAsPercentageValue(p.optionBottom.getValue(), parentHeight);
			} else {
				padding += p.optionBottom.getAsPixels(this);
			}
		}
		return padding;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
//		int childCount = getChildCount();
//		int wFromSpec = MeasureSpec.getSize(widthMeasureSpec);
//		int hFromSpec = MeasureSpec.getSize(heightMeasureSpec);
//		int wSuggested = getSuggestedMinimumWidth();
//		int hSuggested = getSuggestedMinimumHeight();
//		int w = Math.max(wFromSpec, wSuggested);
//		int wMode = MeasureSpec.getMode(widthMeasureSpec);
//		int h = Math.max(hFromSpec, hSuggested);
//		int hMode = MeasureSpec.getMode(heightMeasureSpec);
//
//		int maxWidth = 0;
//		int maxHeight = 0;
//
//		// Used for horizontal layout only
//		int horizontalRowWidth = 0;
//		int horizontalRowHeight = 0;
//
//		for(int i = 0; i < childCount; i++) {
//			View child = getChildAt(i);
//			if (child.getVisibility() != View.GONE) {
//				constrainChild(child, w, wMode, h, hMode);
//			}
//
//			int childWidth = child.getMeasuredWidth();
//			int childHeight = child.getMeasuredHeight();
//			if (child.getVisibility() != View.GONE) {
//				childWidth += getViewWidthPadding(child, w);
//				childHeight += getViewHeightPadding(child, h);
//			}
//
//			if (isHorizontalArrangement()) {
//				if (enableHorizontalWrap) {
//
//					if ((horizontalRowWidth + childWidth) > w) {
//						horizontalRowWidth = childWidth;
//						maxHeight += horizontalRowHeight;
//						horizontalRowHeight = childHeight;
//
//					} else {
//						horizontalRowWidth += childWidth;
//						maxWidth = Math.max(maxWidth, horizontalRowWidth);
//					}
//
//				} else {
//					// For horizontal layout without wrap, just keep on adding the widths since it doesn't wrap
//					maxWidth += childWidth;
//				}
//				horizontalRowHeight = Math.max(horizontalRowHeight, childHeight);
//
//			} else {
//				maxWidth = Math.max(maxWidth, childWidth);
//
//				if (isVerticalArrangement()) {
//					maxHeight += childHeight;
//				} else {
//					maxHeight = Math.max(maxHeight, childHeight);
//				}
//			}
//		}
//
//		// Add height for last row in horizontal layout
//		if (isHorizontalArrangement()) {
//			maxHeight += horizontalRowHeight;
//		}
//
//		// account for padding
//		maxWidth += getPaddingLeft() + getPaddingRight();
//		maxHeight += getPaddingTop() + getPaddingBottom();
//
//		// Account for border
//		//int padding = Math.round(borderHelper.calculatePadding());
//		//maxWidth += padding;
//		//maxHeight += padding;
//
//		// check minimums
//		maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());
//		maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
//
//		int measuredWidth = getMeasuredWidth(maxWidth, widthMeasureSpec);
//		int measuredHeight = getMeasuredHeight(maxHeight,heightMeasureSpec);
//		setMeasuredDimension(measuredWidth, measuredHeight);
//		
		
		
		
		
		
		
		
		if(proxy.get() != null) {
			if(proxy.get().getProperty(TiC.PROPERTY_ID) != null) {
				android.util.Log.d("On measure ! ", (String)proxy.get().getProperty(TiC.PROPERTY_ID) );
			}
			else {
				android.util.Log.d("On measure ! ", "no ID" );
			}
		}
		
		int childCount = getChildCount();
		int wFromSpec = MeasureSpec.getSize(widthMeasureSpec);
		int hFromSpec = MeasureSpec.getSize(heightMeasureSpec);
		int wSuggested = getSuggestedMinimumWidth();
		int hSuggested = getSuggestedMinimumHeight();
		int w = Math.max(wFromSpec, wSuggested);
		int wMode = MeasureSpec.getMode(widthMeasureSpec);
		int h = Math.max(hFromSpec, hSuggested);
		int hMode = MeasureSpec.getMode(heightMeasureSpec);

		int maxWidth = 0;
		int maxHeight = 0;

		// Used for horizontal layout only
		int horizontalRowWidth = 0;
		int horizontalRowHeight = 0;
		
		int totalFixedWidth = 0;
		int totalFixedHeight = 0;
		
		float sumFlex = 0.0f;
		
		int marginTop = 0;
		int marginLeft = 0;
		int marginRight = 0;
		int marginBottom = 0;
		
		int HMargins = 0;
		int VMargins = 0;

		for (int i = 0; i < childCount; i++) {

			View child = getChildAt(i);
			if (child.getVisibility() != View.GONE) {
				constrainChild(child, w, wMode, h, hMode);
			}

			int childWidth = child.getMeasuredWidth();
			int childHeight = child.getMeasuredHeight();
			if (child.getVisibility() != View.GONE) {
				childWidth += getViewWidthPadding(child, w);
				childHeight += getViewHeightPadding(child, h);
			}

			// android.util.Log.d("ourView is ", new String().format("%s",
			// proxy.peekView().class));

			if (isHorizontalArrangement()) {
				if (enableHorizontalWrap) {

					if ((horizontalRowWidth + childWidth) > w) {
						horizontalRowWidth = childWidth;
						maxHeight += horizontalRowHeight;
						horizontalRowHeight = childHeight;

					} else {
						horizontalRowWidth += childWidth;
						maxWidth = Math.max(maxWidth, horizontalRowWidth);
					}

				} else {
					// For horizontal layout without wrap, just keep on adding
					// the widths since it doesn't wrap
					maxWidth += childWidth;
				}
				horizontalRowHeight = Math.max(horizontalRowHeight, childHeight);

			} else {
				maxWidth = Math.max(maxWidth, childWidth);

				if (isVerticalArrangement()) {
					maxHeight += childHeight;
				} else {
					maxHeight = Math.max(maxHeight, childHeight);
				}
			}
			
			TiCompositeLayout.LayoutParams params = (TiCompositeLayout.LayoutParams) child.getLayoutParams();
			if (params.optionWidth != null) {
				totalFixedWidth += params.optionWidth.getIntValue();
				android.util.Log.d("this width is ", new Integer(params.optionWidth.getIntValue()).toString());
			}
			if (params.optionHeight != null) {
				totalFixedHeight += params.optionHeight.getIntValue();
				android.util.Log.d("this height is ", new Integer(params.optionHeight.getIntValue()).toString());
			}

			android.util.Log.d("current totalFixedWidth is --------> ", new Integer(totalFixedWidth).toString());
			android.util.Log.d("current totalFixedHeight is --------> ", new Integer(totalFixedHeight).toString());
			
			if(isHorizontalArrangement()) {
				if (params.flex != null && params.optionWidth == null) {
					android.util.Log.d("params.flex != null and optionWidth != null", "true");
					if (params.flex.getValue() > 0.0001f) {
						sumFlex += params.flex.getValue();
						android.util.Log.d("this flex is ", new Float(params.flex.getValue()).toString());
					}
				}
//				if (params.optionLeft != null) {
//					HMargins += params.optionLeft.getIntValue();
//				}
//				if (params.optionRight != null) {
//					HMargins += params.optionRight.getIntValue();
//				}
			}
			else {
				if (params.flex != null && params.optionHeight == null) { 
					android.util.Log.d("params.flex != null and optionHeight != null", "true");
					if (params.flex.getValue() > 0.0001f) {
						sumFlex += params.flex.getValue();
						android.util.Log.d("this flex is ", new Float(params.flex.getValue()).toString());
					}
				}
//					if (params.optionTop != null) {
//						VMargins += params.optionTop.getIntValue();
//					}
//					if (params.optionBottom != null) {
//						VMargins += params.optionBottom.getIntValue();
//					}

			}
		}

		// Add height for last row in horizontal layout
		if (isHorizontalArrangement()) {
			maxHeight += horizontalRowHeight;
		}

		// account for padding
		maxWidth += getPaddingLeft() + getPaddingRight();
		maxHeight += getPaddingTop() + getPaddingBottom();

		// Account for border
		// int padding = Math.round(borderHelper.calculatePadding());
		// maxWidth += padding;
		// maxHeight += padding;

		// check minimums
		maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());
		maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());

		int measuredWidth = getMeasuredWidth(maxWidth, widthMeasureSpec);
		int measuredHeight = getMeasuredHeight(maxHeight, heightMeasureSpec);
		
		
		int remainFlexableWidth = measuredWidth - totalFixedWidth; // - HMargins;
		int remainFlexableHeight = measuredHeight - totalFixedHeight; // - VMargins;
		
		android.util.Log.d("this remainFlexableWidth is ", new Integer(remainFlexableWidth).toString());
		android.util.Log.d("this remainFlexableHeight is ", new Integer(remainFlexableHeight).toString());
		
		for (int i = 0; i < childCount; i++) {
			View child = getChildAt(i);
			android.util.Log.d("this child is ", child.getClass().getName());
			TiCompositeLayout.LayoutParams params = (TiCompositeLayout.LayoutParams) child.getLayoutParams();
			if(isHorizontalArrangement()) {
				if(params.flex != null && params.optionWidth == null) {
					//eg: flex = 2
					int childCalcWidth = (int)(remainFlexableWidth * params.flex.getValue() / sumFlex + 0.5) ;
					android.util.Log.d("this w and h are ", new Integer(childCalcWidth).toString() +", "+new Integer(measuredHeight).toString());
					//((TiView)child).setMeasuredDimension(childCalcWidth, measuredHeight);
					constrainChild(child, childCalcWidth, wMode, measuredHeight, hMode);
				}
//				else if(params.flex == null && params.optionWidth != null) {
//					//eg: width=2
//					//((TiView)child).setMeasuredDimension(params.optionWidth.getIntValue(), measuredHeight);
//					constrainChild(child, params.optionWidth.getIntValue(), wMode, measuredHeight, hMode);
//				}
//				else {
//					//eg: null property
//					//((TiView)child).setMeasuredDimension(w, h);
//					constrainChild(child, maxWidth, wMode, maxHeight, hMode);
//				}
			}
			else {
				if(params.flex != null && params.optionHeight == null) {
					int childCalcHeight = (int)(remainFlexableHeight * params.flex.getValue() / sumFlex + 0.5) ;
					android.util.Log.d("this w and h are ", new Integer(measuredWidth).toString() +", "+new Integer(childCalcHeight).toString());
					//((TiView)child).setMeasuredDimension(measuredWidth, childCalcHeight);
					constrainChild(child, measuredWidth, wMode, childCalcHeight, hMode);
				}
//				else if(params.flex == null && params.optionHeight != null) {
//					//eg: width=2
//					//((TiView)child).setMeasuredDimension(measuredWidth, params.optionHeight.getIntValue());
//					constrainChild(child, measuredWidth, wMode, params.optionHeight.getIntValue(), hMode);
//				}
//				else {
//					//eg: null property
//					//((TiView)child).setMeasuredDimension(w, h);
//					constrainChild(child, maxWidth, wMode, maxHeight, hMode);
//				}
			}
		}
		
		setMeasuredDimension(measuredWidth, measuredHeight);
	}

	protected void constrainChild(View child, int width, int wMode, int height, int hMode)
	{
		LayoutParams p = (LayoutParams) child.getLayoutParams();

		int sizeFillConflicts[] = { NOT_SET, NOT_SET };
		boolean checkedForConflict = false;

		// If autoFillsWidth is false, and optionWidth is null, then we use size behavior.
		int childDimension = LayoutParams.WRAP_CONTENT;
		if (p.optionWidth != null) {
			if (p.optionWidth.isUnitPercent() && width > 0) {
				childDimension = getAsPercentageValue(p.optionWidth.getValue(), width);
			} else {
				childDimension = p.optionWidth.getAsPixels(this);
			}
		} else {
			if (p.autoFillsWidth) {
				childDimension = LayoutParams.FILL_PARENT;
			} 
			else {
				// Look for sizeFill conflicts
				hasSizeFillConflict(child, sizeFillConflicts, true);
				checkedForConflict = true;
				if (sizeFillConflicts[0] == HAS_SIZE_FILL_CONFLICT) {
					childDimension = LayoutParams.FILL_PARENT;
				}
			}
		}

		int widthPadding = getViewWidthPadding(child, width);
		int widthSpec = ViewGroup.getChildMeasureSpec(MeasureSpec.makeMeasureSpec(width, wMode), widthPadding,
			childDimension);
		// If autoFillsHeight is false, and optionHeight is null, then we use size behavior.
		childDimension = LayoutParams.WRAP_CONTENT;
		if (p.optionHeight != null) {
			if (p.optionHeight.isUnitPercent() && height > 0) {
				childDimension = getAsPercentageValue(p.optionHeight.getValue(), height);
			} else {
				childDimension = p.optionHeight.getAsPixels(this);
			}
		} else {
			// If we already checked for conflicts before, we don't need to again
			if (p.autoFillsHeight || (checkedForConflict && sizeFillConflicts[1] == HAS_SIZE_FILL_CONFLICT)) {
				childDimension = LayoutParams.FILL_PARENT;
			} else if (!checkedForConflict) {
				hasSizeFillConflict(child, sizeFillConflicts, true);
				if (sizeFillConflicts[1] == HAS_SIZE_FILL_CONFLICT) {
					childDimension = LayoutParams.FILL_PARENT;
				}
			}
		}

		int heightPadding = getViewHeightPadding(child, height);
		int heightSpec = ViewGroup.getChildMeasureSpec(MeasureSpec.makeMeasureSpec(height, hMode), heightPadding,
			childDimension);

		child.measure(widthSpec, heightSpec);
		// Useful for debugging.
		// int childWidth = child.getMeasuredWidth();
		// int childHeight = child.getMeasuredHeight();
	}

	// Try to calculate width from pins, if we couldn't calculate from pins or we don't need to, then return the
	// measured width
	private int calculateWidthFromPins(LayoutParams params, int parentLeft, int parentRight, int parentWidth,
		int measuredWidth)
	{
		int width = measuredWidth;

		if (params.optionWidth != null || params.sizeOrFillWidthEnabled) {
			return width;
		}

		TiDimension left = params.optionLeft;
		TiDimension centerX = params.optionCenterX;
		TiDimension right = params.optionRight;

		if (left != null) {
			if (centerX != null) {
				width = (centerX.getAsPixels(this) - left.getAsPixels(this) - parentLeft) * 2;
			} else if (right != null) {
				width = parentWidth - right.getAsPixels(this) - left.getAsPixels(this);
			}
		} else if (centerX != null && right != null) {
			width = (parentRight - right.getAsPixels(this) - centerX.getAsPixels(this)) * 2;
		}
		return width;
	}

	// Try to calculate height from pins, if we couldn't calculate from pins or we don't need to, then return the
	// measured height
	private int calculateHeightFromPins(LayoutParams params, int parentTop, int parentBottom, int parentHeight,
		int measuredHeight)
	{
		int height = measuredHeight;

		// Return if we don't need undefined behavior
		if (params.optionHeight != null || params.sizeOrFillHeightEnabled) {
			return height;
		}

		TiDimension top = params.optionTop;
		TiDimension centerY = params.optionCenterY;
		TiDimension bottom = params.optionBottom;

		if (top != null) {
			if (centerY != null) {
				height = (centerY.getAsPixels(this) - parentTop - top.getAsPixels(this)) * 2;
			} else if (bottom != null) {
				height = parentHeight - top.getAsPixels(this) - bottom.getAsPixels(this);
			}
		} else if (centerY != null && bottom != null) {
			height = (parentBottom - bottom.getAsPixels(this) - centerY.getAsPixels(this)) * 2;
		}

		return height;
	}

	protected int getMeasuredWidth(int maxWidth, int widthSpec)
	{
		return resolveSize(maxWidth, widthSpec);
	}

	protected int getMeasuredHeight(int maxHeight, int heightSpec)
	{
		return resolveSize(maxHeight, heightSpec);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b)
	{
		int count = getChildCount();

		int left = 0;
		int top = 0;
		int right = r - l;
		int bottom = b - t;

		if (needsSort) {
			viewSorter.clear();
			if (count > 1) { // No need to sort one item.
				for(int i = 0; i < count; i++) {
					View child = getChildAt(i);
					TiCompositeLayout.LayoutParams params =
						(TiCompositeLayout.LayoutParams) child.getLayoutParams();
					params.index = i;
					viewSorter.add(child);
				}

				detachAllViewsFromParent();
				int i = 0;
				for (View child : viewSorter) {
					attachViewToParent(child, i++, child.getLayoutParams());
				}
			}
			needsSort = false;
		}
		// viewSorter is not needed after this. It's a source of
		// memory leaks if it retains the views it's holding.
		viewSorter.clear();

		int[] horizontal = new int[2];
		int[] vertical = new int[2];

		int currentHeight = 0; // Used by vertical arrangement calcs

		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			TiCompositeLayout.LayoutParams params =
				(TiCompositeLayout.LayoutParams) child.getLayoutParams();
			if (child.getVisibility() != View.GONE) {
				// Dimension is required from Measure. Positioning is determined here.

				int childMeasuredHeight = child.getMeasuredHeight();
				int childMeasuredWidth = child.getMeasuredWidth();

				if (isHorizontalArrangement()) {
					if (i == 0)  {
						horizontalLayoutCurrentLeft = left;
						horizontalLayoutLineHeight = 0;
						horizontalLayoutTopBuffer = 0;
						horizontalLayoutLastIndexBeforeWrap = 0;
						horiztonalLayoutPreviousRight = 0;
						updateRowForHorizontalWrap(right, i);
					}
					computeHorizontalLayoutPosition(params, childMeasuredWidth, childMeasuredHeight, right, top, bottom, horizontal, vertical, i);

				} else {
					// Try to calculate width/height from pins, and default to measured width/height. We have to do this in
					// onLayout since we can't get the correct top, bottom, left, and right values inside constrainChild().
					childMeasuredHeight = calculateHeightFromPins(params, top, bottom, getHeight(), childMeasuredHeight);
					childMeasuredWidth = calculateWidthFromPins(params, left, right, getWidth(), childMeasuredWidth);

					computePosition(this, params.optionLeft, params.optionCenterX, params.optionRight, childMeasuredWidth, left, right, horizontal);
					if (isVerticalArrangement()) {
						computeVerticalLayoutPosition(currentHeight, params.optionTop, childMeasuredHeight, top, vertical,
							bottom);
						// Include bottom in height calculation for vertical layout (used as padding)
						TiDimension optionBottom = params.optionBottom;
						if (optionBottom != null) {
							currentHeight += optionBottom.getAsPixels(this);
						}
					} else {
						computePosition(this, params.optionTop, params.optionCenterY, params.optionBottom, childMeasuredHeight, top, bottom, vertical);
					}
				}

				Log.d(TAG, child.getClass().getName() + " {" + horizontal[0] + "," + vertical[0] + "," + horizontal[1] + ","
					+ vertical[1] + "}", Log.DEBUG_MODE);

				int newWidth = horizontal[1] - horizontal[0];
				int newHeight = vertical[1] - vertical[0];
				// If the old child measurements do not match the new measurements that we calculated, then update the
				// child measurements accordingly
				if (newWidth != child.getMeasuredWidth()
					|| newHeight != child.getMeasuredHeight()) {
					int newWidthSpec = MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY);
					int newHeightSpec = MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY);
					child.measure(newWidthSpec, newHeightSpec);
				}

				if (!TiApplication.getInstance().isRootActivityAvailable()) {
					Activity currentActivity = TiApplication.getAppCurrentActivity();
					if (currentActivity instanceof TiLaunchActivity) {
						if (!((TiLaunchActivity) currentActivity).isJSActivity()) {
							Log.w(TAG, "The root activity is no longer available.  Skipping layout pass.", Log.DEBUG_MODE);
							return;
						}
					}
				}

				child.layout(horizontal[0], vertical[0], horizontal[1], vertical[1]);

				currentHeight += newHeight;
				if (params.optionTop != null) {
					currentHeight += params.optionTop.getAsPixels(this);
				}
			}
		}

		TiViewProxy viewProxy = (proxy == null ? null : proxy.get());
		TiUIHelper.firePostLayoutEvent(viewProxy);

//		
//		if(proxy.get() != null) {
//			if(proxy.get().getProperty(TiC.PROPERTY_ID) != null) {
//				android.util.Log.d("On Layout ! ", (String)proxy.get().getProperty(TiC.PROPERTY_ID) );
//			}
//			else {
//				android.util.Log.d("On Layout ! ", "no ID" );
//			}
//		}
//		
//		android.util.Log.d("l ==>", new Integer(l).toString());
//		android.util.Log.d("t ==>", new Integer(t).toString());
//		android.util.Log.d("r ==>", new Integer(r).toString());
//		android.util.Log.d("b ==>", new Integer(b).toString());
//
//		int count = getChildCount();
//
//		String str = String.format("%d", count);
//		android.util.Log.d("onLayout child count is ", str);
//
//		int left = 0;
//		int top = 0;
//		int right = r - l;
//		int bottom = b - t;
//
//		double sumFlex = 0.0f;
//		ArrayList<TiCompositeLayout.LayoutParams> offsetValues = new ArrayList<TiCompositeLayout.LayoutParams>();
//
//		if (needsSort) {
//			viewSorter.clear();
//			if (count > 1) { // No need to sort one item.
//				for (int i = 0; i < count; i++) {
//					View child = getChildAt(i);
//					TiCompositeLayout.LayoutParams params = (TiCompositeLayout.LayoutParams) child.getLayoutParams();
//					params.index = i;
//					viewSorter.add(child);
//				}
//
//				detachAllViewsFromParent();
//				int i = 0;
//				for (View child : viewSorter) {
//					attachViewToParent(child, i++, child.getLayoutParams());
//				}
//			}
//			needsSort = false;
//		}
//		// viewSorter is not needed after this. It's a source of
//		// memory leaks if it retains the views it's holding.
//		viewSorter.clear();
//		
//		
//		int marginTop = 0;
//		int marginLeft = 0;
//		int marginRight = 0;
//		int marginBottom = 0;
//		int lastRight = 0;
//		int lastBottom = 0;
//		
//		
//		for (int i = 0; i < count; i++) {
//			View child = getChildAt(i);
//			android.util.Log.d("this child is ", child.getClass().getName());
//			TiCompositeLayout.LayoutParams params = (TiCompositeLayout.LayoutParams) child.getLayoutParams();
//			
//			if (params.marginTop != null) {
//				marginTop = params.marginTop.getIntValue();
//			} else {
//				marginTop = 0;
//			}
//			if (params.marginLeft != null) {
//				marginLeft = params.marginLeft.getIntValue();
//			} else {
//				marginLeft = 0;
//			}
//			if (params.marginRight != null) {
//				marginRight = params.marginRight.getIntValue();
//			} else {
//				marginRight = 0;
//			}
//			if (params.marginBottom != null) {
//				marginBottom = params.marginBottom.getIntValue();
//			} else {
//				marginBottom = 0;
//			}
//			android.util.Log.d("marginTop ==>", new Integer(marginTop).toString());
//			android.util.Log.d("marginLeft ==>", new Integer(marginLeft).toString());
//			android.util.Log.d("marginRight ==>", new Integer(marginRight).toString());
//			android.util.Log.d("marginBottom ==>", new Integer(marginBottom).toString());
//			
//			child.layout(lastRight + marginLeft, 
//					lastBottom + marginTop, 
//					lastRight + marginLeft + child.getMeasuredWidth(), 
//					lastBottom + marginTop + child.getMeasuredHeight());
//			
//			if (isHorizontalArrangement()) {
//				lastRight = lastRight + child.getMeasuredWidth() + marginLeft + marginRight;
//			} else {
//				lastBottom = lastBottom + child.getMeasuredHeight() + marginTop + marginBottom;
//			}
//			
//		}
//
//		TiViewProxy viewProxy = (proxy == null ? null : proxy.get());
//		TiUIHelper.firePostLayoutEvent(viewProxy);
}

	// option0 is left/top, option1 is right/bottom
	public static void computePosition(View parent, TiDimension leftOrTop, TiDimension optionCenter, TiDimension rightOrBottom,
		int measuredSize, int layoutPosition0, int layoutPosition1, int[] pos)
	{
		int dist = layoutPosition1 - layoutPosition0;
		if (leftOrTop != null) {
			// peg left/top
			int leftOrTopPixels = leftOrTop.getAsPixels(parent);
			pos[0] = layoutPosition0 + leftOrTopPixels;
			pos[1] = layoutPosition0 + leftOrTopPixels + measuredSize;
		} else if (optionCenter != null && optionCenter.getValue() != 0.0) {
			// Don't calculate position based on center dimension if it's 0.0
			int halfSize = measuredSize / 2;
			pos[0] = layoutPosition0 + optionCenter.getAsPixels(parent) - halfSize;
			pos[1] = pos[0] + measuredSize;
		} else if (rightOrBottom != null) {
			// peg right/bottom
			int rightOrBottomPixels = rightOrBottom.getAsPixels(parent);
			pos[0] = dist - rightOrBottomPixels - measuredSize;
			pos[1] = dist - rightOrBottomPixels;
		} else {
			// Center
			int offset = (dist - measuredSize) / 2;
			pos[0] = layoutPosition0 + offset;
			pos[1] = pos[0] + measuredSize;
		}
	}

	/*
	 * Set the opacity of the view using View.setAlpha if available.
	 *
	 * @param alpha the opacity of the view
	 * @return true if opacity was set, otherwise false if View.setAlpha failed or was not available.
	 */
	private boolean nativeSetAlpha(float alpha)
	{
		if (Build.VERSION.SDK_INT < 11) {
			// Only available in API level 11 or higher.
			return false;
		}

		if (setAlphaMethod == null) {
			try {
				setAlphaMethod = getClass().getMethod("setAlpha", float.class);
			} catch (NoSuchMethodException e) {
				Log.w(TAG, "Unable to find setAlpha() method.", e, Log.DEBUG_MODE);
				return false;
			}
		}

		try {
			setAlphaMethod.invoke(this, alpha);
		} catch (Exception e) {
			Log.e(TAG, "Failed to call setAlpha().", e);
			return false;
		}

		return true;
	}

	/*
	 * Set the alpha of the view. Provides backwards compatibility
	 * with older versions of Android which don't support View.setAlpha().
	 *
	 * @param alpha the opacity of the view
	 */
	public void setAlphaCompat(float alpha)
	{
		// Try using the native setAlpha() method first.
		if (nativeSetAlpha(alpha)) {
			return;
		}

		// If setAlpha() is not supported on this platform,
		// use the backwards compatibility workaround.
		// See dispatchDraw() for details.
		this.alpha = alpha;
	}

	@Override
	protected void dispatchDraw(Canvas canvas)
	{
		// To support alpha in older versions of Android (API level less than 11),
		// create a new layer to draw the children. Specify the alpha value to use
		// later when we transfer this layer back onto the canvas.
		if (alpha < 1.0f) {
			Rect bounds = new Rect();
			getDrawingRect(bounds);
			canvas.saveLayerAlpha(new RectF(bounds), Math.round(alpha * 255), Canvas.ALL_SAVE_FLAG);
		}

		super.dispatchDraw(canvas);

		if (alpha < 1.0f) {
			// Restore the canvas once the children have been drawn to the layer.
			// This will draw the layer's offscreen bitmap onto the canvas using
			// the alpha value we specified earlier.
			canvas.restore();
		}
	}

	private void computeVerticalLayoutPosition(int currentHeight, TiDimension optionTop, int measuredHeight, int layoutTop,
		int[] pos, int maxBottom)
	{
		int top = layoutTop + currentHeight;
		if (optionTop != null) {
			top += optionTop.getAsPixels(this);
		}
		// cap the bottom to make sure views don't go off-screen when user supplies a height value that is >= screen
		// height and this view is below another view in vertical layout.
		int bottom = Math.min(top + measuredHeight, maxBottom);
		pos[0] = top;
		pos[1] = bottom;
	}

	private void computeHorizontalLayoutPosition(TiCompositeLayout.LayoutParams params, int measuredWidth,
		int measuredHeight, int layoutRight, int layoutTop, int layoutBottom, int[] hpos, int[] vpos, int currentIndex)
	{

		TiDimension optionLeft = params.optionLeft;
		TiDimension optionRight = params.optionRight;
		int left = horizontalLayoutCurrentLeft + horiztonalLayoutPreviousRight;
		int optionLeftValue = 0;
		if (optionLeft != null) {
			optionLeftValue = optionLeft.getAsPixels(this);
			left += optionLeftValue;
		}
		horiztonalLayoutPreviousRight = (optionRight == null) ? 0 : optionRight.getAsPixels(this);

		int right = left + measuredWidth;
		if (enableHorizontalWrap && ((right + horiztonalLayoutPreviousRight) > layoutRight)) {
			// Too long for the current "line" that it's on. Need to move it down.
			left = optionLeftValue;
			right = measuredWidth + left;
			horizontalLayoutTopBuffer = horizontalLayoutTopBuffer + horizontalLayoutLineHeight;
			horizontalLayoutLineHeight = 0;
		}
		hpos[0] = left;
		hpos[1] = right;
		horizontalLayoutCurrentLeft = right;

		if (enableHorizontalWrap) {
			// Don't update row on the first iteration since we already do it beforehand
			if (currentIndex != 0 && currentIndex > horizontalLayoutLastIndexBeforeWrap) {
				updateRowForHorizontalWrap(layoutRight, currentIndex);
			}
			measuredHeight = calculateHeightFromPins(params, horizontalLayoutTopBuffer, horizontalLayoutTopBuffer
				+ horizontalLayoutLineHeight, horizontalLayoutLineHeight, measuredHeight);
			layoutBottom = horizontalLayoutLineHeight;
		}

		// Get vertical position into vpos
		computePosition(this, params.optionTop, params.optionCenterY, params.optionBottom, measuredHeight, layoutTop,
			layoutBottom, vpos);
		// account for moving the item "down" to later line(s) if there has been wrapping.
		vpos[0] = vpos[0] + horizontalLayoutTopBuffer;
		vpos[1] = vpos[1] + horizontalLayoutTopBuffer;
	}

	private void updateRowForHorizontalWrap(int maxRight, int currentIndex)
	{
		int rowWidth = 0;
		int rowHeight = 0;
		int i = 0;
		int parentHeight = getHeight();
		horizontalLayoutLineHeight = 0;

		for (i = currentIndex; i < getChildCount(); i++) {
			View child = getChildAt(i);
			// Calculate row width/height with padding
			rowWidth += child.getMeasuredWidth() + getViewWidthPadding(child, getWidth());
			rowHeight = child.getMeasuredHeight() + getViewHeightPadding(child, parentHeight);

			if (rowWidth > maxRight) {
				horizontalLayoutLastIndexBeforeWrap = i - 1;
				return;

			} else if (rowWidth == maxRight) {
				break;
			}

			if (horizontalLayoutLineHeight < rowHeight) {
				horizontalLayoutLineHeight = rowHeight;
			}
		}

		if (horizontalLayoutLineHeight < rowHeight) {
			horizontalLayoutLineHeight = rowHeight;
		}
		horizontalLayoutLastIndexBeforeWrap = i;
	}

	// Determine whether we have a conflict where a parent has size behavior, and child has fill behavior.
	private boolean hasSizeFillConflict(View parent, int[] conflicts, boolean firstIteration)
	{
		if (parent instanceof TiCompositeLayout) {
			TiCompositeLayout currentLayout = (TiCompositeLayout) parent;
			LayoutParams currentParams = (LayoutParams) currentLayout.getLayoutParams();

			// During the first iteration, the parent view needs to have size behavior.
			if (firstIteration && (currentParams.autoFillsWidth || currentParams.optionWidth != null)) {
				conflicts[0] = NO_SIZE_FILL_CONFLICT;
			}
			if (firstIteration && (currentParams.autoFillsHeight || currentParams.optionHeight != null)) {
				conflicts[1] = NO_SIZE_FILL_CONFLICT;
			}

			// We don't check for sizeOrFillHeightEnabled. The calculations during the measure phase (which includes
			// this method) will be adjusted to undefined behavior accordingly during the layout phase.
			// sizeOrFillHeightEnabled is used during the layout phase to determine whether we want to use the fill/size
			// measurements that we got from the measure phase.
			if (currentParams.autoFillsWidth && currentParams.optionWidth == null && conflicts[0] == NOT_SET) {
				conflicts[0] = HAS_SIZE_FILL_CONFLICT;
			}
			if (currentParams.autoFillsHeight && currentParams.optionHeight == null && conflicts[1] == NOT_SET) {
				conflicts[1] = HAS_SIZE_FILL_CONFLICT;
			}

			// Stop traversing if we've determined whether there is a conflict for both width and height
			if (conflicts[0] != NOT_SET && conflicts[1] != NOT_SET) {
				return true;
			}

			// If the child has size behavior, continue traversing through children and see if any of them have fill
			// behavior
			for (int i = 0; i < currentLayout.getChildCount(); ++i) {
				if (hasSizeFillConflict(currentLayout.getChildAt(i), conflicts, false)) {
					return true;
				}
			}
		}

		// Default to false if we couldn't find conflicts
		if (firstIteration && conflicts[0] == NOT_SET) {
			conflicts[0] = NO_SIZE_FILL_CONFLICT;
		}
		if (firstIteration && conflicts[1] == NOT_SET) {
			conflicts[1] = NO_SIZE_FILL_CONFLICT;
		}
		return false;
	}

	protected int getWidthMeasureSpec(View child) {
		return MeasureSpec.EXACTLY;
	}

	protected int getHeightMeasureSpec(View child) {
		return MeasureSpec.EXACTLY;
	}

	/**
	 * A TiCompositeLayout specific version of {@link android.view.ViewGroup.LayoutParams}
	 */
	public static class LayoutParams extends ViewGroup.LayoutParams {
		protected int index;

		public int optionZIndex = NOT_SET;
		public TiDimension optionLeft = null;
		public TiDimension optionTop = null;
		public TiDimension optionCenterX = null;
		public TiDimension optionCenterY = null;
		public TiDimension optionRight = null;
		public TiDimension optionBottom = null;
		public TiDimension optionWidth = null;
		public TiDimension optionHeight = null;
		public Ti2DMatrix optionTransform = null;
		
		/*
		 * add by david begin 2013-1-11 support flex layout
		 */
		public TiDimension flex = null;
		public TiDimension marginTop = null;
		public TiDimension marginLeft = null;
		public TiDimension marginRight = null;
		public TiDimension marginBottom = null;
		public TiDimension paddingTop = null;
		public TiDimension paddingLeft = null;
		public TiDimension paddingRight = null;
		public TiDimension paddingBottom = null;
		
		public TiDimension calcWidth;
		public TiDimension calcHeight;

		/* add by david end */

		// This are flags to determine whether we are using fill or size behavior
		public boolean sizeOrFillHeightEnabled = true;
		public boolean sizeOrFillWidthEnabled = true;

		/**
		 * If this is true, and {@link #sizeOrFillWidthEnabled} is true, then the current view will follow the fill
		 * behavior, which fills available parent width. If this value is false and {@link #sizeOrFillWidthEnabled} is
		 * true, then we use the size behavior, which constrains the view width to fit the width of its contents.
		 * 
		 * @module.api
		 */
		public boolean autoFillsWidth = false;

		/**
		 * If this is true, and {@link #sizeOrFillHeightEnabled} is true, then the current view will follow fill
		 * behavior, which fills available parent height. If this value is false and {@link #sizeOrFillHeightEnabled} is
		 * true, then we use the size behavior, which constrains the view height to fit the height of its contents.
		 * 
		 * @module.api
		 */
		public boolean autoFillsHeight = false;

		public LayoutParams()
		{
			super(WRAP_CONTENT, WRAP_CONTENT);

			index = Integer.MIN_VALUE;
		}
	}

	protected boolean isVerticalArrangement()
	{
		return (arrangement == LayoutArrangement.VERTICAL);
	}

	protected boolean isHorizontalArrangement()
	{
		return (arrangement == LayoutArrangement.HORIZONTAL);
	}

	protected boolean isDefaultArrangement()
	{
		return (arrangement == LayoutArrangement.DEFAULT);
	}

	public void setLayoutArrangement(String arrangementProperty)
	{
		if (arrangementProperty != null && arrangementProperty.equals(TiC.LAYOUT_HORIZONTAL)) {
			arrangement = LayoutArrangement.HORIZONTAL;
		} else if (arrangementProperty != null && arrangementProperty.equals(TiC.LAYOUT_VERTICAL)) {
			arrangement = LayoutArrangement.VERTICAL;
		} else {
			arrangement = LayoutArrangement.DEFAULT;
		}
	}

	public void setEnableHorizontalWrap(boolean enable)
	{
		enableHorizontalWrap = enable;
	}

	public void setProxy(TiViewProxy proxy)
	{
		this.proxy = new WeakReference<TiViewProxy>(proxy);
	}

}
