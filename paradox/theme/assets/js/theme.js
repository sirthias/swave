window.theme = {};

// Navigation
(function($) {

	'use strict';

	var $items = $( '.nav-main li.nav-parent' );

	function expand( $li ) {
		$li.children( 'ul.nav-children' ).slideDown( 'fast', function() {
			$li.addClass( 'nav-expanded' );
			$(this).css( 'display', '' );
			ensureVisible( $li );
		});
	}

	function collapse( $li ) {
		$li.children('ul.nav-children' ).slideUp( 'fast', function() {
			$(this).css( 'display', '' );
			$li.removeClass( 'nav-expanded' );
		});
	}

	function ensureVisible( $li ) {
		var scroller = $li.offsetParent();
		if ( !scroller.get(0) ) {
			return false;
		}

		var top = $li.position().top;
		if ( top < 0 ) {
			scroller.animate({
				scrollTop: scroller.scrollTop() + top
			}, 'fast');
		}
	}

	$items.find('> a').on('click', function( ev ) {

		var $anchor = $( this ),
			$prev = $anchor.closest('ul.nav').find('> li.nav-expanded' ),
			$next = $anchor.closest('li');

		if ( $anchor.prop('href') ) {
			var arrowWidth = parseInt(window.getComputedStyle($anchor.get(0), ':after').width, 10) || 0;
			if (ev.offsetX > $anchor.get(0).offsetWidth - arrowWidth) {
				ev.preventDefault();
			}
		}

		if ( $prev.get( 0 ) !== $next.get( 0 ) ) {
			collapse( $prev );
			expand( $next );
		} else {
			collapse( $prev );
		}
	});


}).apply(this, [jQuery]);

// Skeleton
(function(theme, $) {

	'use strict';

	theme = theme || {};

	var $body		= $( 'body' ),
		$html		= $( 'html' ),
		$window		= $( window ),
		isAndroid	= navigator.userAgent.toLowerCase().indexOf('android') > -1;

	// mobile devices with fixed has a lot of issues when focus inputs and others...
	if ( typeof $.browser !== 'undefined' && $.browser.mobile && $html.hasClass('fixed') ) {
		$html.removeClass( 'fixed' ).addClass( 'scroll' );
	}

	var Skeleton = {

		options: {
			sidebars: {
				menu: '#content-menu',
				left: '#sidebar-left',
				right: '#sidebar-right'
			}
		},

		customScroll: ( !Modernizr.overflowscrolling && !isAndroid && $.fn.nanoScroller !== 'undefined'),

		initialize: function() {
			this
				.setVars()
				.build()
				.events();
		},

		setVars: function() {
			this.sidebars = {};

			this.sidebars.left = {
				$el: $( this.options.sidebars.left )
			};

			this.sidebars.right = {
				$el: $( this.options.sidebars.right ),
				isOpened: $html.hasClass( 'sidebar-right-opened' )
			};

			this.sidebars.menu = {
				$el: $( this.options.sidebars.menu ),
				isOpened: $html.hasClass( 'inner-menu-opened' )
			};

			return this;
		},

		build: function() {

			if ( typeof $.browser !== 'undefined' && $.browser.mobile ) {
				$html.addClass( 'mobile-device' );
			} else {
				$html.addClass( 'no-mobile-device' );
			}

			$html.addClass( 'custom-scroll' );
			if ( this.customScroll ) {
				this.buildSidebarLeft();
				this.buildContentMenu();
			}

			this.buildSidebarRight();

			return this;
		},

		events: function() {
			if ( this.customScroll ) {
				this.eventsSidebarLeft();
			}

			this.eventsSidebarRight();
			this.eventsContentMenu();

			if ( typeof $.browser !== 'undefined' && !this.customScroll && isAndroid ) {
				this.fixScroll();
			}

			return this;
		},

		fixScroll: function() {
			var _self = this;

			$window
				.on( 'sidebar-left-opened sidebar-right-toggle', function( e, data ) {
					_self.preventBodyScrollToggle( data.added );
				});

		},

		buildSidebarLeft: function() {

			var initialPosition = 0;

			this.sidebars.left.isOpened = !$html.hasClass( 'sidebar-left-collapsed' ) || $html.hasClass( 'sidebar-left-opened' );

			this.sidebars.left.$nano = this.sidebars.left.$el.find( '.nano' );

			if (typeof localStorage !== 'undefined') {
				this.sidebars.left.$nano.on('update', function(e, values) {
					localStorage.setItem('sidebar-left-position', values.position);
				});

				if (localStorage.getItem('sidebar-left-position') !== null) {
					initialPosition = localStorage.getItem('sidebar-left-position');
					this.sidebars.left.$el.find( '.nano-content').scrollTop(initialPosition);
				}
			}

			this.sidebars.left.$nano.nanoScroller({
				scrollTop: initialPosition,
				alwaysVisible: true,
				preventPageScrolling: $html.hasClass( 'fixed' )
			});

			return this;
		},

		eventsSidebarLeft: function() {

			var _self = this,
				$nano = this.sidebars.left.$nano;

			var open = function() {
				if ( _self.sidebars.left.isOpened ) {
					return close();
				}

				_self.sidebars.left.isOpened = true;

				$html.addClass( 'sidebar-left-opened' );

				$window.trigger( 'sidebar-left-toggle', {
					added: true,
					removed: false
				});

				$html.on( 'click.close-left-sidebar', function(e) {
					e.stopPropagation();
					close(e);
				});


			};

			var close = function(e) {
				if ( !!e && !!e.target && ($(e.target).closest( '.sidebar-left' ).get(0) || !$(e.target).closest( 'html' ).get(0)) ) {
					e.preventDefault();
					return false;
				} else {

					$html.removeClass( 'sidebar-left-opened' );
					$html.off( 'click.close-left-sidebar' );

					$window.trigger( 'sidebar-left-toggle', {
						added: false,
						removed: true
					});

					_self.sidebars.left.isOpened = !$html.hasClass( 'sidebar-left-collapsed' );

				}
			};

			var updateNanoScroll = function() {
				if ( $.support.transition ) {
					$nano.nanoScroller();
					$nano
						.one('bsTransitionEnd', updateNanoScroll)
						.emulateTransitionEnd(150)
				} else {
					updateNanoScroll();
				}
			};

			var isToggler = function( element ) {
				return $(element).data('fire-event') === 'sidebar-left-toggle' || $(element).parents().data('fire-event') === 'sidebar-left-toggle';
			};

			this.sidebars.left.$el
				.on( 'click', function() {
					updateNanoScroll();
				})
				.on('touchend', function(e) {
					_self.sidebars.left.isOpened = !$html.hasClass( 'sidebar-left-collapsed' ) || $html.hasClass( 'sidebar-left-opened' );
					if ( !_self.sidebars.left.isOpened && !isToggler(e.target) ) {
						e.stopPropagation();
						e.preventDefault();
						open();
					}
				});

			$nano
				.on( 'mouseenter', function() {
					if ( $html.hasClass( 'sidebar-left-collapsed' ) ) {
						$nano.nanoScroller();
					}
				})
				.on( 'mouseleave', function() {
					if ( $html.hasClass( 'sidebar-left-collapsed' ) ) {
						$nano.nanoScroller();
					}
				});

			$window.on( 'sidebar-left-toggle', function(e, toggle) {
				if ( toggle.removed ) {
					$html.removeClass( 'sidebar-left-opened' );
					$html.off( 'click.close-left-sidebar' );
				}
			});

			return this;
		},

		buildSidebarRight: function() {
			this.sidebars.right.isOpened = $html.hasClass( 'sidebar-right-opened' );

			if ( this.customScroll ) {
				this.sidebars.right.$nano = this.sidebars.right.$el.find( '.nano' );

				this.sidebars.right.$nano.nanoScroller({
					alwaysVisible: true,
					preventPageScrolling: true
				});
			}

			return this;
		},

		eventsSidebarRight: function() {
			var _self = this;

			var open = function() {
				if ( _self.sidebars.right.isOpened ) {
					return close();
				}

				_self.sidebars.right.isOpened = true;

				$html.addClass( 'sidebar-right-opened' );

				$window.trigger( 'sidebar-right-toggle', {
					added: true,
					removed: false
				});

				$html.on( 'click.close-right-sidebar', function(e) {
					e.stopPropagation();
					close(e);
				});
			};

			var close = function(e) {
				if ( !!e && !!e.target && ($(e.target).closest( '.sidebar-right' ).get(0) || !$(e.target).closest( 'html' ).get(0)) ) {
					return false;
				}

				$html.removeClass( 'sidebar-right-opened' );
				$html.off( 'click.close-right-sidebar' );

				$window.trigger( 'sidebar-right-toggle', {
					added: false,
					removed: true
				});

				_self.sidebars.right.isOpened = false;
			};

			var bind = function() {
				$('[data-open="sidebar-right"]').on('click', function(e) {
					var $el = $(this);
					e.stopPropagation();

					if ( $el.is('a') )
						e.preventDefault();

					open();
				});
			};

			this.sidebars.right.$el.find( '.mobile-close' )
				.on( 'click', function( e ) {
					e.preventDefault();
					$html.trigger( 'click.close-right-sidebar' );
				});

			bind();

			return this;
		},

		buildContentMenu: function() {
			if ( !$html.hasClass( 'fixed' ) ) {
				return false;
			}

			this.sidebars.menu.$nano = this.sidebars.menu.$el.find( '.nano' );

			this.sidebars.menu.$nano.nanoScroller({
				alwaysVisible: true,
				preventPageScrolling: true
			});

			return this;
		},

		eventsContentMenu: function() {
			var _self = this;

			var open = function() {
				if ( _self.sidebars.menu.isOpened ) {
					return close();
				}

				_self.sidebars.menu.isOpened = true;

				$html.addClass( 'inner-menu-opened' );

				$window.trigger( 'inner-menu-toggle', {
					added: true,
					removed: false
				});

				$html.on( 'click.close-inner-menu', function(e) {

					close(e);
				});

			};

			var close = function(e) {
				var hasEvent,
					hasTarget,
					isCollapseButton,
					isInsideModal,
					isInsideInnerMenu,
					isInsideHTML,
					$target;

				hasEvent = !!e;
				hasTarget = hasEvent && !!e.target;

				if ( hasTarget ) {
					$target = $(e.target);
				}

				isCollapseButton = hasTarget && !!$target.closest( '.inner-menu-collapse' ).get(0);
				isInsideModal = hasTarget && !!$target.closest( '.mfp-wrap' ).get(0);
				isInsideInnerMenu = hasTarget && !!$target.closest( '.inner-menu' ).get(0);
				isInsideHTML = hasTarget && !!$target.closest( 'html' ).get(0);

				if ( (!isCollapseButton && (isInsideInnerMenu || !isInsideHTML)) || isInsideModal ) {
					return false;
				}

				e.stopPropagation();

				$html.removeClass( 'inner-menu-opened' );
				$html.off( 'click.close-inner-menu' );

				$window.trigger( 'inner-menu-toggle', {
					added: false,
					removed: true
				});

				_self.sidebars.menu.isOpened = false;
			};

			var bind = function() {
				$('[data-open="inner-menu"]').on('click', function(e) {
					var $el = $(this);
					e.stopPropagation();

					if ( $el.is('a') )
						e.preventDefault();

					open();
				});
			};

			bind();

			/* Nano Scroll */
			if ( $html.hasClass( 'fixed' ) ) {
				var $nano = this.sidebars.menu.$nano;

				var updateNanoScroll = function() {
					if ( $.support.transition ) {
						$nano.nanoScroller();
						$nano
							.one('bsTransitionEnd', updateNanoScroll)
							.emulateTransitionEnd(150)
					} else {
						updateNanoScroll();
					}
				};

				this.sidebars.menu.$el
					.on( 'click', function() {
						updateNanoScroll();
					});
			}

			return this;
		},

		preventBodyScrollToggle: function( shouldPrevent, $el ) {
			setTimeout(function() {
				if ( shouldPrevent ) {
					$body
						.data( 'scrollTop', $body.get(0).scrollTop )
						.css({
							position: 'fixed',
							top: $body.get(0).scrollTop * -1
						})
				} else {
					$body
						.css({
							position: '',
							top: ''
						})
						.scrollTop( $body.data( 'scrollTop' ) );
				}
			}, 150);
		}

	};

	// expose to scope
	$.extend(theme, {
		Skeleton: Skeleton
	});

}).apply(this, [window.theme, jQuery]);

// Base
(function(theme, $) {

	'use strict';

	theme = theme || {};

	theme.Skeleton.initialize();

}).apply(this, [window.theme, jQuery]);

// Scrollable
(function(theme, $) {

	theme = theme || {};

	var instanceName = '__scrollable';

	var PluginScrollable = function($el, opts) {
		return this.initialize($el, opts);
	};

	PluginScrollable.updateModals = function() {
		PluginScrollable.updateBootstrapModal();
	};

	PluginScrollable.updateBootstrapModal = function() {
		var updateBoostrapModal;

		updateBoostrapModal = typeof $.fn.modal !== 'undefined';
		updateBoostrapModal = updateBoostrapModal && typeof $.fn.modal.Constructor !== 'undefined';
		updateBoostrapModal = updateBoostrapModal && typeof $.fn.modal.Constructor.prototype !== 'undefined';
		updateBoostrapModal = updateBoostrapModal && typeof $.fn.modal.Constructor.prototype.enforceFocus !== 'undefined';

		if ( !updateBoostrapModal ) {
			return false;
		}

		var originalFocus = $.fn.modal.Constructor.prototype.enforceFocus;
		$.fn.modal.Constructor.prototype.enforceFocus = function() {
			originalFocus.apply( this );

			var $scrollable = this.$element.find('.scrollable');
			if ( $scrollable ) {
				if ( $.isFunction($.fn['themePluginScrollable'])  ) {
					$scrollable.themePluginScrollable();
				}

				if ( $.isFunction($.fn['nanoScroller']) ) {
					$scrollable.nanoScroller();
				}
			}
		};
	};

	PluginScrollable.defaults = {
		contentClass: 'scrollable-content',
		paneClass: 'scrollable-pane',
		sliderClass: 'scrollable-slider',
		alwaysVisible: true,
		preventPageScrolling: true
	};

	PluginScrollable.prototype = {
		initialize: function($el, opts) {
			if ( $el.data( instanceName ) ) {
				return this;
			}

			this.$el = $el;

			this
				.setData()
				.setOptions(opts)
				.build();

			return this;
		},

		setData: function() {
			this.$el.data(instanceName, this);

			return this;
		},

		setOptions: function(opts) {
			this.options = $.extend(true, {}, PluginScrollable.defaults, opts, {
				wrapper: this.$el
			});

			return this;
		},

		build: function() {
			this.options.wrapper.nanoScroller(this.options);

			return this;
		}
	};

	// expose to scope
	$.extend(theme, {
		PluginScrollable: PluginScrollable
	});

	// jquery plugin
	$.fn.themePluginScrollable = function(opts) {
		return this.each(function() {
			var $this = $(this);

			if ($this.data(instanceName)) {
				return $this.data(instanceName);
			} else {
				return new PluginScrollable($this, opts);
			}

		});
	};

	$(function() {
		PluginScrollable.updateModals();
	});

}).apply(this, [window.theme, jQuery]);

// Bootstrap Toggle
(function($) {

	'use strict';

	var $window = $( window );

	var toggleClass = function( $el ) {
		if ( !!$el.data('toggleClassBinded') ) {
			return false;
		}

		var $target,
			className,
			eventName;

		$target = $( $el.attr('data-target') );
		className = $el.attr('data-toggle-class');
		eventName = $el.attr('data-fire-event');


		$el.on('click.toggleClass', function(e) {
			e.preventDefault();
			$target.toggleClass( className );

			var hasClass = $target.hasClass( className );

			if ( !!eventName ) {
				$window.trigger( eventName, {
					added: hasClass,
					removed: !hasClass
				});
			}
		});

		$el.data('toggleClassBinded', true);

		return true;
	};

	$(function() {
		$('[data-toggle-class][data-target]').each(function() {
			toggleClass( $(this) );
		});
	});

}).apply(this, [jQuery]);