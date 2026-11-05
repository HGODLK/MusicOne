package com.musicone

/**
 * 行内判断只关心与焦点的相对位置：焦点在本行前三句以外、或后两句以外时，表现都一致。
 * 把焦点收进这一窗口，远处行在单步切句时值不变，不随整片歌词重组。
 */
internal fun lyricRowFocusWindow(index: Int, current: Int): Int = current.coerceIn(index - 3, index + 2)
